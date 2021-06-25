package com.seer.srd.junyi

import com.seer.srd.BusinessError
import com.seer.srd.NoSuchStoreSite
import com.seer.srd.db.MongoDBManager
import com.seer.srd.junyi.handlers.SignalTypes
import com.seer.srd.robottask.*
import com.seer.srd.robottask.RobotTaskService.saveNewRobotTask
import com.seer.srd.route.service.PlantModelService
import com.seer.srd.route.service.VehicleService
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.util.HttpClient
import com.seer.srd.util.loadConfig
import com.seer.srd.vehicle.Vehicle
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

object CommonUtil {
    private val logger = LoggerFactory.getLogger(CommonUtil::class.java)

    private val customConfig = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

    private val mesHttpClient = HttpClient.buildHttpClient(customConfig.mesUrl, MesHttpClient::class.java)

    var enablePopupTask = customConfig.enablePopupTask

    private val PROCESS_INFOS: MutableMap<String, ProcessInfo> = mutableMapOf(
        "P010" to
            ProcessInfo("TaskDefL1ToP010", "VIR-P010", "P010Finished"),
        "P020" to
            ProcessInfo("TaskDefP010ToP020", "VIR-P020", "P020Finished"),
        "P090" to
            ProcessInfo("TaskDefF1ToP090", "VIR-P090", "P090Finished"),
        "P100" to
            ProcessInfo("TaskDefP090ToP100", "VIR-P100", "P100Finished"),
        "P130" to
            ProcessInfo("TaskDefJ1ToP130", "VIR-P130", "P130Finished"),
        "模组入壳" to
            ProcessInfo("TaskDefLoadModule", "VIR-LoadModule", "LoadModuleFinished"),
        "模组固定" to
            ProcessInfo("TaskDefFixModule", "VIR-FixModule", "FixModuleFinished"),
        "高压铜线安装" to
            ProcessInfo("TaskDefLoadHighVolCable", "VIR-LoadHighVolCable", "LoadHighVolCableFinished"),
        "水冷板检测" to
            ProcessInfo("TaskDefBoardTest", "VIR-BoardTest", "BoardTestFinished"),
        "线束安装" to
            ProcessInfo("TaskDefLoadWire", "VIR-LoadWire", "LoadWireFinished"),
        "螺丝紧固" to
            ProcessInfo("TaskDefFixScrew", "VIR-FixScrew", "FixScrewFinished"),
        "气密性检测" to
            ProcessInfo("TaskDefAirTightnessTest", "VIR-AirTightnessTest", "AirTightnessTestFinished")
    )

    fun getPassword(): String {
        return customConfig.password
    }

    fun processFinished(procName: String): Int {
        logger.info("$procName - 放行.")
        val procInfo = PROCESS_INFOS[procName] ?: throw BusinessError("无法识别未定义的任务【$procName】")
        try {
            // 呼叫任务未完成前，报错
            val task = MongoDBManager.collection<RobotTask>()
                .findOne(RobotTask::def eq procInfo.taskDef, RobotTask::state eq RobotTaskState.Created)

            if (task != null) {
                throw BusinessError("【$procName】任务未完成【${task.id}】is [${task.state}]，请检查任务状态.")
            }

            val siteId = procInfo.virSiteId
            val site = StoreSiteService.getExistedStoreSiteById(siteId)
            if (site.content.isBlank())
                throw BusinessError("没有未放行的任务，请在【呼叫】之后再【放行】！")

            val remark = procInfo.remark
            try {
                StoreSiteService.unlockSiteIfLocked(siteId, remark)
                StoreSiteService.setSiteContent(siteId, "", remark)
            } catch (e: Exception) {
                throw BusinessError("【$procName - 放行】失败，请确认库位【$siteId】未锁定再操作！")
            }

            return 201

        } catch (e: Exception) {
            throw BusinessError("【放行】操作失败 - $e")
        }
    }

    // 获取库位/工作站的站点名称
    fun getPointOfSite(siteId: String): String {
        val loc = PlantModelService.getPlantModel().locations[siteId]
            ?: throw BusinessError("不存在库位【$siteId】")
        return loc.attachedLinks.first().point
    }

    // 获取目标库位/工作站上的机器人列表
    fun numberOfIdleVehiclesOnSite(siteId: String): List<String> {
        val point = getPointOfSite(siteId)
        // 记录停靠在此库位的机器人
        val vehicleNames: MutableList<String> = mutableListOf()
        for (vehicle in VehicleService.listVehicles()) {
            // 获取 在点位上 && 在线接单 && 空闲 的机器人
            if (vehicle.currentPosition == point
                // 正在充电的机器人也视为可用机器人
                && (vehicle.state == Vehicle.State.IDLE || vehicle.state == Vehicle.State.CHARGING)
                && vehicle.integrationLevel == Vehicle.IntegrationLevel.TO_BE_UTILIZED) {
                vehicleNames.add(vehicle.name)
            }
        }
        if (vehicleNames.isEmpty()) {
            val message = "库位[$siteId]上无在线接单的空闲机器人, 请检查此库位上机器人的实际状态！"
            logger.error(message)
//            throw BusinessError(message)
        } else
            logger.debug("库位[$siteId]上存在在线接单的空闲机器人$vehicleNames.")
        return vehicleNames
    }

    fun vehicleOnSiteNotThrowError(siteId: String): Boolean {
        try {
            vehicleOnSiteAndIdle(siteId)
            return true
        } catch (e: Exception) {
            logger.error("vehicleOnSiteNotThrowError - ${e.message}")
        }
        return false
    }

    fun vehicleOnSiteAndIdle(siteId: String): String {
        val vehicles = numberOfIdleVehiclesOnSite(siteId)

        if (vehicles.isEmpty()) throw BusinessError("工位【$siteId】上没有空闲的机器人，请确认前置任务状态!")

        if (vehicles.size > 1)
            throw BusinessError("有多个机器人【$vehicles】在【${siteId}】请确认机器人位置")

        val site = StoreSiteService.getStoreSiteById(siteId)
            ?: throw BusinessError("不存在库位【$siteId】，请确认库位信息")

        if (!site.filled || site.locked)
            throw BusinessError("库位【$siteId】的状态不是仅被占用的，请确认库位状态")

        val vehicleName = vehicles.first()
        logger.debug("只有一个机器人[$vehicleName]在库位[$siteId]上。")
        return vehicleName
    }

    fun vehicleNotOnSite(siteId: String) {

        val vehicleNames = numberOfVehiclesOnSiteSilently(siteId)

        if (vehicleNames.isNotEmpty()) throw BusinessError("工位【$siteId】上存在机器人!")

        StoreSiteService.getStoreSiteById(siteId)
            ?: throw BusinessError("不存在库位【$siteId】，请确认库位信息")
    }

    fun lockSiteIfNotLockButOnlyRecordError(siteId: String, taskId: String, description: String, remark: String) {
        // 锁定未锁定的库位 如果失败不抛异常
        try {
            StoreSiteService.lockSiteIfNotLock(siteId, taskId, remark)
            logger.info("[$description]，lock site=$siteId success")
        } catch (e: Exception) {
            logger.info("[$description]，lock site=$siteId failed for ${e.message}")
        }
    }

    fun unlockSiteIfLockButOnlyRecordError(siteId: String, description: String, remark: String) {
        try {
            StoreSiteService.unlockSiteIfLocked(siteId, remark)
            logger.info("$description，update $siteId to unlocked success")
        } catch (e: Exception) {
            logger.info("$description，update $siteId to unlocked failed for ${e.message}")
        }
    }

    fun lockFromSiteAndAssignVehicleNoThrow(landMark: String, location: String, taskId: String): VehicleAndItsLoc {
        // 判断目标点位上是否存在机器人
        val vehicleNames: MutableList<String> = mutableListOf()
        for (vehicle in VehicleService.listVehicles()) {
            if (vehicle.currentPosition == landMark) {
                vehicleNames.add(vehicle.name)
            }
        }

        if (vehicleNames.isEmpty()) {
            logger.error("上一个任务还未完成，等待中...")
            return VehicleAndItsLoc()
        }

        if (vehicleNames.size > 1) {
            logger.error("有多个机器人【$vehicleNames】在【${landMark}】请确认机器人位置")
            return VehicleAndItsLoc()
        }

        // 判断起点库位状态
        val fromSite = StoreSiteService.getStoreSiteById(location)
        if (fromSite == null) {
            logger.error("不存在库位【$location】，请检查库位")
            return VehicleAndItsLoc()
        }
        if (!fromSite.filled) {
            logger.error("库位【$location】未被占用，请确认之前任务状态.")
            return VehicleAndItsLoc()
        }
        if (fromSite.locked) {
            logger.error("库位【$location】被锁定，请确认前置任务状态和库位状态")
            return VehicleAndItsLoc()
        }

        // 锁定起点库位，用StoreSiteService处理，会有操作记录
        try {
            StoreSiteService.lockSiteIfNotLock(fromSite.id, taskId, "by task=$taskId")
        } catch (e: Exception) {
            logger.error("Lock fromSite and assign vehicle failed! $e")
        }

        return VehicleAndItsLoc(vehicleNames.first(), location, landMark)
    }

    @Synchronized
    fun processSitesByType(type: String, taskId: String): VehicleAndItsLoc {
        val sites = MongoDBManager.collection<StoreSite>()
            .find(StoreSite::type eq type).toMutableList()
        if (sites.isEmpty()) throw BusinessError("不存在类型为【$type】的库位")
        for (site in sites) {
            val siteId = site.id
            val vehicles = numberOfVehiclesOnSiteSilently(siteId)
            if (vehicles.size > 1) throw BusinessError("有多个机器人【$vehicles】在【$siteId】请确认机器人位置")
            val idleVehicleOnSite = vehicles.isNotEmpty()
            if (type != "G") {
                // 【目的】：选择可用的机器人，并锁定此机器人停靠的库位作为任务的起点
                // 需要报错的情况
                // locked && filled && no-vehicle       异常 可能会导致当前任务占用编号较大的库位作为起点，但任务却不能被立即执行
                // locked && filled && vehicle          异常 这个库位已经被其他任务占用了，不能作为当前任务的起点，但是可以从编号较大的库位中选择起点
                // locked && !filled && no-vehicle      异常 此库位已经是其他任务的终点
                // locked && !filled && vehicle         异常
                // !locked && filled && no-vehicle      异常 仅处于占用状态的库位上一定是有机器人的。
                // !locked && filled && vehicle         正常
                // !locked && !filled && no-vehicle     正常
                // !locked && !filled && vehicle        异常 未被锁定且未被占用的库位删一定是没有机器人的
                if (site.locked && site.filled) {
                    throw BusinessError("库位【$siteId】被其他任务占用（被锁定，且被占用），等待。。。")
                }
                if (site.locked && !site.filled) {
                    val txt = if (type == "G-A") "F-1" else "区域G"
                    throw BusinessError(
                        "无可用的机器人和起点，库位【${siteId}】可能已被“${txt}的放行任务”或者“区域【$type】补位任务”锁定了！")
                }
                if (!site.locked && site.filled && !idleVehicleOnSite)
                    throw BusinessError("库位【${siteId}】状态异常，库位仅处于被占用状态，但是库位上没有机器人！")
                if (!site.locked && !site.filled && idleVehicleOnSite)
                    throw BusinessError("库位【${siteId}】状态异常，库位上有机器人，但是库位未被占用也未被锁定！")
            }
            if (idleVehicleOnSite) {
                // 锁定起点库位，用StoreSiteService处理，会有操作记录
                try {
                    StoreSiteService.lockSiteIfNotLock(siteId, taskId, "by task=$taskId")
                } catch (e: Exception) {
                    logger.error("Lock fromSite and assign vehicle failed! $e")
                }
                return VehicleAndItsLoc(vehicles.first(), siteId, getPointOfSite(siteId))
            }
        }
        return VehicleAndItsLoc()
    }

    fun recordTaskIdIntoSiteContent(siteId: String, taskId: String, remark: String) {
        val site = StoreSiteService.getExistedStoreSiteById(siteId)
        val content = site.content
        if (!content.isBlank())
            throw BusinessError("记录任务ID失败，库位【$siteId】上已经有信息【$content】")

        StoreSiteService.setSiteContent(siteId, taskId, remark)
    }

    fun updateVirSiteStatus(siteId: String, description: String, remark: String) {
        unlockSiteIfLockButOnlyRecordError(siteId, description, remark)
        StoreSiteService.setSiteContent(siteId, "", remark)
    }

    fun markTaskBatteryTaskCreated(taskCount: Int) {
        val siteId = "VIR-TakeBattery"
        val site = StoreSiteService.getStoreSiteById(siteId)
            ?: throw NoSuchStoreSite("不存在虚拟库位【$siteId】，请在系统空闲时添加此库位！")
        if (!site.locked) throw BusinessError("【AGV放行】任务已经生成，请勿重复下发！")
        StoreSiteService.unlockSiteIfLocked(siteId, "create take battery tasks success.")
        StoreSiteService.setSiteContent(siteId, taskCount.toString(), "record take battery tasks count")
    }

    fun setTaskBatteryTaskCountIntoSiteContent(taskId: String, newCount: Int) {
        val siteId = "VIR-TakeBattery"
        val siteId2 = "VIR-ToCharge"
        val remark = "update take battery tasks count"
        if (newCount <= 0) {
            lockSiteIfNotLockButOnlyRecordError(siteId, taskId, "充放电测试工位放行任务全部结束", "$remark to 0")
            lockSiteIfNotLockButOnlyRecordError(siteId2, taskId, "充放电测试工位放行任务全部结束", "battery test finished")
            StoreSiteService.setSiteContent(siteId2, "", "battery test finished")
        }
        val content = if (newCount > 0) newCount.toString() else ""
        StoreSiteService.setSiteContent(siteId, content, remark)
    }

    @Synchronized
    fun unlockSiteVirToChargeAndMarkNumber(taskCount: Int) {
        val siteId = "VIR-ToCharge"
        val remark = "create agv do charge tasks success."
        val siteC = StoreSiteService.getStoreSiteById(siteId)
            ?: throw NoSuchStoreSite("不存在虚拟库位【$siteId】，请在系统空闲时添加此库位！")
        if (!siteC.locked) throw BusinessError("【AGV充电】任务已经生成，请勿重复下发！")
        // 为了统一所有虚拟库位的初始状态，VIR-ToCharge=locked: 未成功创建【AGV充电】任务；否则已经创建【AGV充电】任务
        StoreSiteService.unlockSiteIfLocked(siteC.id, remark)
        StoreSiteService.setSiteContent(siteId, taskCount.toString(), remark)
    }

    fun lockSiteVirToChargeByCondition(newCount: Int) {
        val siteId = "VIR-ToCharge"
        val remark = "update agvDoCharge tasks count"
//        if (newCount <= 0)
//            lockSiteIfNotLockButOnlyRecordError(siteId, "agv充电任务全部完成", "$remark to 0")
        val content = if (newCount > 0) newCount.toString() else "AGV充电完成，待放行"
        StoreSiteService.setSiteContent(siteId, content, remark)
    }

    @Synchronized
    fun selectAndLockTheFirstAvailableSiteByType(taskId: String, siteType: String): String {
        // 筛选可用的空库位
        val siteIds: MutableList<String> = mutableListOf()
        val indexs: MutableList<Int> = mutableListOf()
        val sites = MongoDBManager.collection<StoreSite>()
            .find(StoreSite::type eq siteType, StoreSite::locked eq false, StoreSite::filled eq false).toMutableList()
        if (sites.isEmpty()) throw BusinessError("充放电测试等待区没有可用空库位！")
        // 忽略被机器人占用的库位
        sites.removeIf { numberOfVehiclesOnSiteSilently(it.id).isNotEmpty() }
        for (index in 1..4) {
            sites.forEach {
                if (it.id == "$siteType-${index}") {
                    siteIds.add(it.id)
                    indexs.add(index)
                }
            }
        }
        logger.info("可用空库位：$siteIds，indexs:$indexs")
        // 再次筛选空库位
        val dest = when (indexs.size) {
            1 -> {
                if (indexs.first() == 4) siteIds.first()
                else throw BusinessError("机器人无法到达库位，${siteIds}")
            }
            2 -> {
                when {
                    indexs.first() == 3 -> siteIds.first()
                    indexs.last() == 4 -> siteIds.last()
                    else -> throw BusinessError("机器人无法到达库位，${siteIds}")
                }
            }
            3 -> {
                when {
                    indexs.first() == 2 -> siteIds.first()
                    indexs[1] == 3 -> siteIds[1]
                    indexs[2] == 4 -> siteIds[2]
                    else -> throw BusinessError("机器人无法到达库位，${siteIds}")
                }
            }
            4 -> {
                if (indexs.first() == 1) siteIds.first()
                else throw BusinessError("库位顺序不对，${siteIds}")
            }
            else -> throw BusinessError("充放电测试等待区没有可用空库位，${siteIds}")
        }

        // 锁定库位
        lockSiteIfNotLockButOnlyRecordError(dest, taskId, "Lock site by type ", "Lock site by type ")

        return dest
    }

    fun submit(type: SignalTypes, remark: String) {
        try {
            when (type) {
                SignalTypes.P030 -> {
                    val response = mesHttpClient.loadBottomShellFinished().execute()
                    val resCode = response.code()
                    val message = response.message()
                    logger.info("$remark, Res code: $resCode, message:$message.")
                    // 解锁虚拟库位的状态 VIR-P030
                    if (resCode == 201) {
                        updateVirSiteStatus("VIR-P030", "P030", "P030Finished")
                    } else throw BusinessError("TellMesP030FinishedFailed [$resCode] $message")
                }
                SignalTypes.LoadTopShell -> {
                    val response = mesHttpClient.loadTopShellFinished().execute()
                    val resCode = response.code()
                    val message = response.message()
                    logger.info("$remark, Res code: $resCode, message:${response.message()}.")
                    // 解锁虚拟库位的状态 VIR-LoadBottomShell
                    if (resCode == 201) {
                        updateVirSiteStatus("VIR-LoadTopShell", "上壳体安装放行", "loadTopShellFinished")
                    } else throw BusinessError("TellMesLoadTopShellFinishedFailed [$resCode] $message")
                }
                SignalTypes.VehicleAtLoadModulePos -> {
                    val response = mesHttpClient.vehicleAtLoadModulePos().execute()
                    val resCode = response.code()
                    val message = response.message()
                    logger.info("$remark, Res code: $resCode, message:$message.")
                    if (resCode != 201) throw BusinessError("TellMesVehicleAtLoadModulePos [$resCode] $message")
                }
                SignalTypes.VehicleAtLoadModuleNextPos -> {
                    val response = mesHttpClient.vehicleAtLoadModuleNextPos().execute()
                    val resCode = response.code()
                    val message = response.message()
                    logger.info("$remark, Res code: $resCode, message:$message.")
                    if (resCode != 201) throw BusinessError("TellMesVehicleAtLoadModuleNextPos [$resCode] $message")
                }
                SignalTypes.VehicleAtFixScrewPos -> {
                    val response = mesHttpClient.vehicleAtFixScrewPos().execute()
                    val resCode = response.code()
                    val message = response.message()
                    logger.info("$remark, Res code: $resCode, message:$message.")
                    if (resCode != 201) throw BusinessError("TellMesVehicleAtFixScrewPos [$resCode] $message")
                }
                SignalTypes.VehicleAtFixScrewNextPos -> {
                    val response = mesHttpClient.vehicleAtFixScrewNextPos().execute()
                    val resCode = response.code()
                    val message = response.message()
                    logger.info("$remark, Res code: $resCode, message:$message.")
                    if (resCode != 201) throw BusinessError("TellMesVehicleAtFixScrewNextPos [$resCode] $message")
                }
                SignalTypes.FixModuleAlreadyCalled -> {
                    val response = mesHttpClient.fixModuleAlreadyCalled().execute()
                    val resCode = response.code()
                    val message = response.message()
                    logger.info("$remark, Res code: $resCode, message:$message.")
                    if (resCode != 201) throw BusinessError("TellMesFixModuleAlreadyCalled [$resCode] $message")
                }
                SignalTypes.AirTightnessAlreadyCalled -> {
                    val response = mesHttpClient.airTightnessTestAlreadyCalled().execute()
                    val resCode = response.code()
                    val message = response.message()
                    logger.info("$remark, Res code: $resCode, message:$message.")
                    if (resCode != 201) throw BusinessError("TellMesAirTightnessAlreadyCalled [$resCode] $message")
                }
            }
        } catch (e: Exception) {
            logger.error("$remark, ${e.message}")
            throw BusinessError("$remark, ${e.message}")
        }
    }

    @Synchronized
    fun createPopupTask() {
        if (!enablePopupTask) return
        try {
            val types = customConfig.bufferForPopUpTask
            for (type in types) {
                try {
                    val sites = StoreSiteService.listStoreSites().filter { it.type == type }
                        .sortedBy { it.id.split("-").last().toInt() }   // 按照库位名称进行排序
                    var expectedIndex = 0
                    sites.forEachIndexed to@{ i, to ->
                        // logger.debug("------- type=$type, mark=$expectedIndex, i=$i")
                        // i==expectedIndex 可保证创建补位任务时，可以按照库位索引分段创建任务；不分段可能会导致系统长时间无法创建此区域的补位任务
                        if (i == expectedIndex
                            && !to.filled && !to.locked                             // 当前库位未锁定且未占用
                            && numberOfVehiclesOnSiteSilently(to.id).isEmpty()      // 当前库位上未停靠机器人
                        ) {
                            val sitesRemains = sites.subList(i + 1, sites.size)
                            sitesRemains.forEachIndexed from@{ j, from ->
                                val filled = from.filled
                                val locked = from.locked
                                if (filled) {
                                    if (!locked) {
                                        buildPopupTask(type, from.id, to.id)
                                        logger.debug("build task from=$from")
                                        expectedIndex++
                                    } else {
                                        // 此库位被【充放电测试工位】的呼叫任务或者【补位】任务锁定了
                                        // 后面的库位上即使有符合条件的机器人，也不能创建补位任务
                                        // 但是可以将剩余的库位视为一个区域，再次尝试创建补位任务
                                        // 记录当前库位在 sites 中的索引，from 的下一个库位才是有效的库位
                                        expectedIndex = j + i + 1 + 1    // 第一个 +1 是确定 from 在原集合中的索引
                                        // logger.debug("======11 mark($expectedIndex) = j($j) + i($i) + 1 + 1")
                                    }
                                    return@to
                                } else { // !filled
                                    // 如果库位只是被锁定，或者库位上有机器人，则此库位和编号小于此库位的库位都是不能用的
                                    if (locked || numberOfIdleVehiclesOnSiteSilently(from.id).isNotEmpty()) {
                                        // 理由同 （from.filled && from.locked）
                                        expectedIndex = j + i + 1 + 1    // 第一个 +1 是确定 from 在原集合中的索引
                                        // logger.debug("======010 mark($expectedIndex) = j($j) + i($i) + 1 + 1")
                                        return@to
                                    }
                                    // 库位上有车，但是未锁定且未占用，此库位不能起点和途径点
                                    if (sitesOccupiedByVehicles(listOf(from.id)) && !locked) {
                                        expectedIndex = j + i + 1 + 1    // 第一个 +1 是确定 from 在原集合中的索引
                                        // logger.debug("======011 mark($expectedIndex) = j($j) + i($i) + 1 + 1")
                                        return@to
                                    }
                                }
                            }
                        }
                        if (i >= expectedIndex) expectedIndex++
                    }
                } catch (e: Exception) {
                    logger.error("Try to create popup-task of $type in $types failed, continue . $e")
                }
            }
        } catch (e: Exception) {
            logger.debug("Try to create popup-task failed! $e")
        }
    }

    // 获取目标库位/工作站上的机器人列表 用于处理起点
    private fun numberOfIdleVehiclesOnSiteSilently(siteId: String): List<String> {
        val point = getPointOfSite(siteId)
        // 记录停靠在此库位的机器人
        val vehicleNames: MutableList<String> = mutableListOf()
        for (vehicle in VehicleService.listVehicles()) {
            // 获取 在点位上 && 在线接单 && 空闲 的机器人
            if (vehicle.currentPosition == point
                // 正在充电的机器人也视为可用机器人
                && (vehicle.state == Vehicle.State.IDLE || vehicle.state == Vehicle.State.CHARGING)
                && vehicle.integrationLevel == Vehicle.IntegrationLevel.TO_BE_UTILIZED) {
                vehicleNames.add(vehicle.name)
            }
        }
        return vehicleNames
    }

    // 获取目标库位/工作站上的机器人列表，(用于处理终点)
    private fun numberOfVehiclesOnSiteSilently(siteId: String): List<String> {
        val point = getPointOfSite(siteId)
        // 记录停靠在此库位的机器人
        val vehicleNames: MutableList<String> = mutableListOf()
        for (vehicle in VehicleService.listVehicles()) {
            // 获取在此站点上的机器人名称，不用考虑机器人的状态
            if (vehicle.currentPosition == point) {
                vehicleNames.add(vehicle.name)
            }
        }
        return vehicleNames
    }

    // 指定的库位的集合中是否存被机器人占用的库位
    private fun sitesOccupiedByVehicles(sites: List<String>): Boolean {
        val points = sites.map { getPointOfSite(it) }
        for (v in VehicleService.listVehicles()) {
            if (v.allocations.intersect(points).isNotEmpty()) return true
        }
        return false
    }

    private fun buildPopupTask(type: String, fromSiteId: String, toSiteId: String) {
        var def = "TaskDefPopupTaskGA"  // "TaskDefPopupTaskGA-Complex", "TaskDefPopupTaskGA-Simple"
        try {
            // 获取起点库位上的车辆信息
            val vehicle = vehicleOnSiteAndIdle(fromSiteId)
            // 起点的编号一定是比终点的大的
            val neighbor = getStoreSiteNumber(fromSiteId) - getStoreSiteNumber(toSiteId) == 1
            def = if (neighbor) "TaskDefPopupTaskGA-Simple" else "TaskDefPopupTaskGA-Complex"

            // 并创建对应的补位任务
            val taskDef = getRobotTaskDef(def)
                ?: throw BusinessError("不存在柔性任务【$def】！")
            val popupTask = buildTaskInstanceByDef(taskDef)

            // 立即锁定起点库位和终点库位
            StoreSiteService.lockSiteIfNotLock(fromSiteId, popupTask.id, "create popup-task")
            StoreSiteService.lockSiteIfNotLock(toSiteId, popupTask.id, "create popup-task")

            // 指定执行任务的机器人
            popupTask.transports.forEach { it.intendedRobot = vehicle }
            val pv = popupTask.persistedVariables
            pv["fromSiteId"] = fromSiteId
            pv["toSiteId"] = toSiteId
            if (!neighbor) pv["next"] = "$type-${getStoreSiteNumber(fromSiteId) - 1}"

            // 保存任务
            saveNewRobotTask(popupTask)
        } catch (e: Exception) {
            logger.error("Create task=[$def] failed, $e")
            try {
                // 尝试解锁终点库位
                StoreSiteService.unlockSiteIfLocked(toSiteId, "create popup-task failed")
            } catch (e: Exception) {
                logger.error("Unlock site=$toSiteId failed while Create task=[$def] failed, $e")
            }
        }
    }

    private fun getStoreSiteNumber(siteId: String): Int {
        return siteId.split("-").last().toInt()
    }
}

data class ProcessInfo(
    var taskDef: String = "",
    var virSiteId: String = "",
    var remark: String = ""
)