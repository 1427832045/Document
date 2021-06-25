package com.seer.srd.junyi

import com.mongodb.client.model.Filters
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.junyi.CommonUtil.getPointOfSite
import com.seer.srd.junyi.CommonUtil.lockSiteIfNotLockButOnlyRecordError
import com.seer.srd.junyi.CommonUtil.lockSiteVirToChargeByCondition
import com.seer.srd.junyi.CommonUtil.markTaskBatteryTaskCreated
import com.seer.srd.junyi.CommonUtil.numberOfIdleVehiclesOnSite
import com.seer.srd.junyi.CommonUtil.processSitesByType
import com.seer.srd.junyi.CommonUtil.recordTaskIdIntoSiteContent
import com.seer.srd.junyi.CommonUtil.selectAndLockTheFirstAvailableSiteByType
import com.seer.srd.junyi.CommonUtil.setTaskBatteryTaskCountIntoSiteContent
import com.seer.srd.junyi.CommonUtil.submit
import com.seer.srd.junyi.CommonUtil.unlockSiteIfLockButOnlyRecordError
import com.seer.srd.junyi.CommonUtil.unlockSiteVirToChargeAndMarkNumber
import com.seer.srd.junyi.CommonUtil.vehicleNotOnSite
import com.seer.srd.junyi.CommonUtil.vehicleOnSiteAndIdle
import com.seer.srd.junyi.CommonUtil.vehicleOnSiteNotThrowError
import com.seer.srd.junyi.handlers.SignalTypes
import com.seer.srd.robottask.*
import com.seer.srd.robottask.RobotTaskService.saveNewRobotTask
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import org.litote.kmongo.*
import org.slf4j.LoggerFactory

object ExtraComponents {
    private val logger = LoggerFactory.getLogger(ExtraComponents::class.java)

    val extraComponents: List<TaskComponentDef> = listOf(
        TaskComponentDef(
            "extra", "OnlyOneNotFinishedTaskOfSameDef", "检测是否存在未完成的同类型任务", "",
            false, listOf(
            TaskComponentParam("taskDef", "任务类型", "string")
        ), false) { component, ctx ->
            val taskDef = parseComponentParamValue("taskDef", component, ctx) as String
            logger.debug("检测是否存在未完成的同类型任务，任务类型=$taskDef")

            val task = MongoDBManager.collection<RobotTask>()
                .findOne(RobotTask::def eq taskDef, RobotTask::state eq RobotTaskState.Created)
            if (task != null) throw BusinessError("系统中已经存在未结束的同类型任务【${task.id}】")
        },

        TaskComponentDef(
            "extra", "RecordTaskIdIntoEmptyContentSite", "将任务ID记录到没有内容的库位中", "",
            false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), false) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            val taskId = ctx.task.id
            logger.debug("将任务ID记录到没有内容的库位中，终点库位=$siteId, taskId=$taskId")

            recordTaskIdIntoSiteContent(siteId, taskId, "record taskId")
        },

        TaskComponentDef(
            "extra", "VehicleOnStoreSite", "检测目标库位上存在机器人并指派给任务，若非有且仅有一个，则报错", "",
            false, listOf(
            TaskComponentParam("siteId", "库位", "string")
        ), false) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            val taskId = ctx.task.id
            try {
                val vehicleName = vehicleOnSiteAndIdle(siteId)
                logger.debug("目标库位[$siteId]上存在机器人[$vehicleName], 并指派给任务[$taskId].")
                ctx.task.transports.forEach { it.intendedRobot = vehicleName }
            } catch (e: Exception) {
                logger.error("目标库位[$siteId]上不存在唯一的机器人, 无法指派给任务[$taskId]!")
                throw e
            }
        },

        TaskComponentDef(
            "extra", "VehicleNotOnStoreSite", "检测目标库位上不存在机器人，若有，则报错", "",
            false, listOf(
            TaskComponentParam("siteId", "库位", "string")
        ), false) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            logger.debug("检测目标库位上不存在机器人，库位=$siteId")

            vehicleNotOnSite(siteId)
        },

        TaskComponentDef(
            "extra", "LockFromSiteAndAssignVehicle", "根据起点选择可用机器人，指派给当前任务，并锁定起点", "",
            false, listOf(
            TaskComponentParam("fromLoc", "起点工作站名称", "string")
        ), false) { component, ctx ->
            val fromLoc = parseComponentParamValue("fromLoc", component, ctx) as String
            logger.debug("选择可用机器人，并制定给当前运单，起点工作站名称=$fromLoc")

            // 判断目标点位上是否存在机器人
            val vehicleNames = numberOfIdleVehiclesOnSite(fromLoc)

            if (vehicleNames.isEmpty()) throw BusinessError("工位【$fromLoc】上没有机器人，请确认上一环节任务状态!")

            if (vehicleNames.size > 1)
                throw BusinessError("有多个机器人【$vehicleNames】在【${fromLoc}】请确认机器人位置")

            // 判断起点库位状态
            val fromSite = StoreSiteService.getStoreSiteById(fromLoc)
                ?: throw  BusinessError("不存在库位【$fromLoc】，请检查库位")
            if (!fromSite.filled) throw BusinessError("库位【$fromLoc】未被占用，请确认之前任务状态.")
            if (fromSite.locked) throw BusinessError("库位【$fromLoc】被锁定，请确认前置任务状态和库位状态")

            // 锁定起点库位 如果失败不抛异常
            lockSiteIfNotLockButOnlyRecordError(fromLoc, ctx.task.id, "lock fromSite ", "lock for ${ctx.task.id}")

            val vehicleName = vehicleNames.first()

            // 确认完毕之后，指定机器人给当前任务的每一个运单
            ctx.task.transports.forEach { it.intendedRobot = vehicleName }

        },

        TaskComponentDef(
            "extra", "CheckSignal", "检测信号", "",
            false, listOf(
            TaskComponentParam("signalId", "信号名称", "string")
        ), false) { component, ctx ->
            val signalId = parseComponentParamValue("signalId", component, ctx) as String
            logger.debug("检测信号 $signalId")

            // 检测对应的库位状态
            StoreSiteService.loadStoreSites()
            val site = StoreSiteService.getStoreSiteById(signalId)
                ?: throw  BusinessError("未定义的信号【$signalId】，请检查库位信息")
            if (site.locked) throw BusinessError("未收到确认信号")
        },

        TaskComponentDef(
            "extra", "SelectAndLockAvailableSiteFromArea_GA", "按顺序从目标区域区选择空闲库位，并锁定",
            "", false, listOf(
            TaskComponentParam("siteType", "库位类型", "string")
        ), true) { component, ctx ->
            val siteType = parseComponentParamValue("siteType", component, ctx) as String
            logger.debug("按顺序从充放电测试等待区选择空闲库位 $siteType")

            val result = selectAndLockTheFirstAvailableSiteByType(ctx.task.id, siteType)

            ctx.task.persistedVariables[component.returnName ?: "toSite"] = result
        },

        TaskComponentDef(
            "extra", "AssignVehicleAndLockItsLocation",
            "按顺序从目标库区选择可用机器人，指派给当前任务，并锁定机器人所在库位", "",
            false, listOf(
            TaskComponentParam("siteType", "库位类型", "string")
        ), true) { component, ctx ->
            val siteType = parseComponentParamValue("siteType", component, ctx) as String
            logger.debug("按顺序从目标库区选择可用机器人，指派给当前任务，并锁定机器人所在库位, 库位类型=$siteType")

            // process sites by type [G, G-A, G-B]
            val vAndL = processSitesByType(siteType, ctx.task.id)

            val vehicleName = vAndL.vehicleName
            if (vehicleName == "") throw BusinessError("区域【$siteType】没有机器人！")

            // 确认完毕之后，指定机器人给当前任务的每一个运单
            ctx.task.transports.forEach { it.intendedRobot = vehicleName }

            val returnName = component.returnName ?: "fromSite"
            ctx.task.persistedVariables[returnName] = vAndL.location

            MongoDBManager.collection<RobotTask>().updateOne(
                Filters.and(RobotTask::id eq ctx.task.id),
                set(RobotTask::persistedVariables setTo ctx.task.persistedVariables)
            )
        },

        TaskComponentDef(
            "extra", "CheckFormerSiteUnlocked", "判断当前工位的前置工位是否未被锁定", "",
            false, listOf(
            TaskComponentParam("siteId", "当前库位", "string")
        ), false) { component, ctx ->
            // 用于处理 G-A 和 G-B 两个缓存库区
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            logger.debug("判断当前工位的前置工位是否未被锁定, 当前库位=$siteId")

            val list = siteId.split("-")
            val type = "${list[0]}-${list[1]}"
            val index = list.last().toInt()
            if (index == 1) {
                logger.info("$siteId is the first one of type=$type.")
            } else {
                val newSiteId = "$type-${index - 1}"
                val newSite = StoreSiteService.getExistedStoreSiteById(newSiteId)
                if (newSite.locked)
                    throw BusinessError("库位【$newSiteId】还未解锁，有机器人还未通过【$siteId】，等待...")
            }
        },

        TaskComponentDef(
            "extra", "CreateAGVDoChargeTasks", "批量创建AGV充电任务", "",
            false, listOf(
        ), false) { _, _ ->
            logger.debug("批量创建AGV充电任务")

            // 判断是否存在未完成的【充放电测试】任务
            val task = MongoDBManager.collection<RobotTask>()
                .findOne(RobotTask::def eq "TaskDefBatteryTest", RobotTask::state eq RobotTaskState.Created)
            if (task != null)
                throw BusinessError("【充放电测试】任务未完成【${task.id}】is state=[${task.state}].")

            // 获取被占用的【充放电测试工位】的信息
            val sites = MongoDBManager.collection<StoreSite>()
                .find(StoreSite::type eq "G").toMutableList()
            val briefInfos: MutableList<String> = mutableListOf()
            val availableGSites: MutableList<StoreSite> = mutableListOf()
            sites.map {
                briefInfos.add("siteId=${it.id}, locked=${it.locked}, filled=${it.filled} ")
                if (!it.locked && it.filled && it.content.isNotBlank() && vehicleOnSiteNotThrowError(it.id))
                    availableGSites.add(it)
            }
            logger.debug("current G-sites status: $briefInfos")

            if (availableGSites.isEmpty())
                throw BusinessError("【充放电测试工位】不可用，请检查前置任务和对应的库位状态!")

            // 根据获取到的 G 工位信息，批量创建【AGV充电】任务
            val taskDef = getRobotTaskDef("TaskDefAGVDoCharge")
                ?: throw BusinessError("无法识别任务定义【TaskDefAGVDoCharge】")
            val newTasks: MutableList<RobotTask> = mutableListOf()
            availableGSites.forEach {
                val newTask = buildTaskInstanceByDef(taskDef)
                // 设置任务参数
                val pv = newTask.persistedVariables
                val siteId = it.id
                pv["siteId"] = siteId
                pv["location"] = getPointOfSite(siteId)
                logger.info("task=${newTask.id}, persistedVariables=${newTask.persistedVariables}")
                newTasks += newTask
            }

            unlockSiteVirToChargeAndMarkNumber(newTasks.size)

            newTasks.forEach { saveNewRobotTask(it) }
        },

        TaskComponentDef(
            "extra", "CreateTakeBatteryTasks", "批量创建从充放电测试工位领走料架的任务", "",
            false, listOf(
            TaskComponentParam("force", "强制放行(true)/不强制放行(false)", "string")
        ), false) { component, ctx ->
            val force = parseComponentParamValue("force", component, ctx) as String
            logger.debug("批量创建从充放电测试工位领走料架的任务，强制创建=$force")

            // 查询之前卸货的G库位
            val sites = MongoDBManager.collection<StoreSite>()
                .find(StoreSite::type eq "G", StoreSite::locked eq false, StoreSite::filled eq true).toMutableList()

            if (sites.isEmpty()) throw BusinessError("G-库区没有符合要求的库位，请检查库位状态")
            logger.debug("被机器人占用的充放电测试工位有： ${sites.map { it.id }}")

            val newTasks: MutableList<RobotTask> = mutableListOf()
            if (force == "true") { // 强制创建放行任务，AGV直接从测试工位领走料架
                val taskDef = getRobotTaskDef("TaskDefTakeBatteryForce")
                    ?: throw BusinessError("无法识别任务定义【TaskDefTakeBatteryForce】")
                sites.forEach {
                    val newTask = buildTaskInstanceByDef(taskDef)
                    val pv = newTask.persistedVariables
                    pv["fromSite"] = it.id
                    newTasks += newTask
                }
                markTaskBatteryTaskCreated(newTasks.size)
                newTasks.forEach { saveNewRobotTask(it) }

            } else {    // 非强制创建，AGV从充电位置去充放电测试工位领走料架
                // 根据获取到的 G 工位信息，批量创建任务
                val taskDef = getRobotTaskDef("TaskDefTakeBattery")
                    ?: throw BusinessError("无法识别任务定义【TaskDefTakeBattery】")
                sites.forEach {
                    val newTask = buildTaskInstanceByDef(taskDef)
                    // 设置任务参数
                    val pv = newTask.persistedVariables
                    val gSiteId = it.id
                    pv["toSiteG"] = gSiteId   // 测试工位
                    pv["fromSite"] = "LOC-CP-${gSiteId.last()}"     // 充电位置，需要细化处理（充电位置、充电前置点）
                    logger.info("task=${newTask.id}, persistedVariables=${newTask.persistedVariables}")
                    newTasks += newTask
                }
                markTaskBatteryTaskCreated(newTasks.size)
                newTasks.forEach { saveNewRobotTask(it) }

                // 锁定 VIR-ToCharge ，强制清空【AGV充电】的生成标志
                unlockSiteIfLockButOnlyRecordError("VIR-ToCharge", "create take battery tasks", "create take battery tasks")
            }
        },

        TaskComponentDef(
            "extra", "markCurrTakeBatteryFinished", "标记当前充放电测试放行子任务已经完成", "",
            false, listOf(
        ), false) { _, ctx ->
            val currTaskId = ctx.task.id
            logger.debug("清空充放电测试工位放行信号 - $currTaskId")
            val siteContent = StoreSiteService.getExistedStoreSiteById("VIR-TakeBattery").content.toInt()
            setTaskBatteryTaskCountIntoSiteContent(ctx.task.id, siteContent - 1)
        },

        TaskComponentDef(
            "extra", "markCurrAGVDoChargeFinished", "标记当前AGV充电子任务已经完成", "",
            false, listOf(
        ), false) { _, ctx ->
            val currTaskId = ctx.task.id
            logger.debug("AGV充电任务[$currTaskId] 结束。")
            val siteContent = StoreSiteService.getExistedStoreSiteById("VIR-ToCharge").content
            val value = if (siteContent.isNotBlank()) siteContent.toInt() else 0
            lockSiteVirToChargeByCondition(value - 1)
        },

        TaskComponentDef(
            "extra", "checkSiteContentBlank", "检查库位上没有货物", "",
            false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), false) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            logger.debug("检查库位[$siteId]上存在货物")
            val site = StoreSiteService.getStoreSiteById(siteId) ?: throw BusinessError("不存在库位【$siteId】")
            if (site.content.isNotBlank()) throw BusinessError("库位【$siteId】上有货物！")
        },

        TaskComponentDef(
            "extra", "SelectAndAssignVehicleFromCPAreaAndLockItsSite", "从充电区域选择机器人指派给对应的任务", "",
            false, listOf(
        ), false) { _, _ ->
            logger.debug("批量创建AGV充电任务")

            // 查询之前卸货的G库位
            val sites = MongoDBManager.collection<StoreSite>()
                .find(StoreSite::type eq "G", StoreSite::locked eq false, StoreSite::filled eq true).toMutableList()

            if (sites.isEmpty()) throw BusinessError("G-库区没有符合要求的库位，请检查库位状态")

            // 根据获取到的 G 工位信息，批量创建任务
            val taskDef = getRobotTaskDef("TaskDefTaskBattery")
                ?: throw BusinessError("无法识别任务定义【TaskDefTaskBattery】")
            val newTasks: MutableList<RobotTask> = mutableListOf()
            sites.forEach {
                val newTask = buildTaskInstanceByDef(taskDef)
                // 设置任务参数
                val pv = newTask.persistedVariables
                val gSiteId = it.id
                pv["toSiteG"] = gSiteId   // 测试工位
                pv["fromSite"] = "LOC-CP-${gSiteId.last()}"     // 充电位置，需要细化处理（充电位置、充电前置点）
                logger.info("task=${newTask.id}, persistedVariables=${newTask.persistedVariables}")
                newTasks += newTask
            }
            newTasks.forEach { saveNewRobotTask(it) }
        },

        TaskComponentDef(
            "extra", "SelectCPByGSite", "获取G库位对应的充电库位", "",
            false, listOf(
            TaskComponentParam("gSiteId", "G库位", "string")
        ), true) { component, ctx ->
            val gSiteId = parseComponentParamValue("gSiteId", component, ctx) as String
            val cp = "LOC-CP-${gSiteId.last()}"
            logger.debug("获取G库位对应的充电库位，siteId=$gSiteId, cp=$cp")
            ctx.task.persistedVariables[component.returnName ?: "cp"] = cp
        },

        TaskComponentDef(
            "extra", "VehicleAtLoadModulePos", "SRD-K 告知 MES ，机器人到达模组入壳工位", "",
            false, listOf(
        ), false) { _, ctx ->
            logger.debug("SRD-K 告知 MES ，机器人到达模组入壳工位，task=${ctx.task.id}")
            submit(SignalTypes.VehicleAtLoadModulePos, "SRD-K 告知 MES ，机器人到达模组入壳工位")
        },

        TaskComponentDef(
            "extra", "VehicleAtLoadModuleNextPos", "SRD-K 告知 MES ，机器人到达模组固定工位", "",
            false, listOf(
        ), false) { _, ctx ->
            logger.debug("SRD-K 告知 MES ，机器人到达模组固定工位，task=${ctx.task.id}")
            submit(SignalTypes.VehicleAtLoadModuleNextPos, "SRD-K 告知 MES ，机器人到达模组固定工位")
        },

        TaskComponentDef(
            "extra", "VehicleAtFixScrewPos", "SRD-K 告知 MES ，机器人到达螺丝紧固工位", "",
            false, listOf(
        ), false) { _, ctx ->
            logger.debug("SRD-K 告知 MES ，机器人到达螺丝紧固工位，task=${ctx.task.id}")
            submit(SignalTypes.VehicleAtFixScrewPos, "SRD-K 告知 MES ，机器人到达螺丝紧固工位")
        },

        TaskComponentDef(
            "extra", "VehicleAtFixScrewNextPos", "SRD-K 告知 MES ，机器人到达气密性检测工位", "",
            false, listOf(
        ), false) { _, ctx ->
            logger.debug("SRD-K 告知 MES ，机器人到达气密性检测工位，task=${ctx.task.id}")
            submit(SignalTypes.VehicleAtFixScrewNextPos, "SRD-K 告知 MES ，机器人到达气密性检测工位")
        },

        TaskComponentDef(
            "extra", "TellMesFixModuleAlreadyCalled", "SRD-K 告知 MES ，模组固定工位已经【呼叫】", "",
            false, listOf(
        ), false) { _, ctx ->
            logger.debug("SRD-K 告知 MES ，模组固定工位已经【呼叫】，task=${ctx.task.id}")
            submit(SignalTypes.FixModuleAlreadyCalled, "SRD-K 告知 MES ，模组固定工位已经【呼叫】")
        },

        TaskComponentDef(
            "extra", "TellMesAirTightnessAlreadyCalled", "SRD-K 告知 MES ，气密性检测工位已经【呼叫】", "",
            false, listOf(
        ), false) { _, ctx ->
            logger.debug("SRD-K 告知 MES ，气密性检测工位已经【呼叫】，task=${ctx.task.id}")
            submit(SignalTypes.AirTightnessAlreadyCalled, "SRD-K 告知 MES ，气密性检测工位已经【呼叫】")
        }
    )
}

data class VehicleAndItsLoc(
    var vehicleName: String = "",
    var location: String = "",
    var landMark: String = ""
)