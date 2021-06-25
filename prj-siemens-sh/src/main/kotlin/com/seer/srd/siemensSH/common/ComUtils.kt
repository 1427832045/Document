package com.seer.srd.siemensSH.common

import com.mongodb.client.model.Filters
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.*
import com.seer.srd.route.service.VehicleService
import com.seer.srd.siemensSH.customStoreSite.PlcConfig
import com.seer.srd.siemensSH.phase1.Phase1Utils.plcSites
import com.seer.srd.siemensSH.phase2.Phase2Utils
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.util.loadConfig
import com.seer.srd.vehicle.Vehicle
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import kotlin.collections.toList

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

const val DEF_E_TO_STATION = "TaskDefEToStation"
const val DEF_MAT_TO_PS = "TaskDefTakeMatToPS"
const val DEF_RETURN_EMPTY_TRAY_BACK_TO_STORAGE = "TaskDefReturnEmptyTrayBackToStorage"
const val DEF_RETURN_EMPTY_TRAY_FROM_AE1_TO_AE2 = "TaskDefTakeEmptyTrayFromAE1ToAE2"
const val TRY_NEXT_MARK = "tryNext-"

object ComUtils {
    private val logger = LoggerFactory.getLogger(ComUtils::class.java)

    private val execE2StationTask = ExecE2StationTaskIds()

    private val taskDefsForAGV01: MutableList<String> = mutableListOf()

    private val taskDefsForAGV02: MutableList<String> = mutableListOf()

    init {
        listRobotTaskDefs().forEach {
            val name = it.name
            when (it.transports.first().category) {
                "1" -> taskDefsForAGV01.add(name)
                "2" -> taskDefsForAGV02.add(name)
                else -> logger.debug("undefined category from taskDef=$name!")
            }
        }
    }

    fun getMenuIdAndSiteType(): Map<String, String> {
        val result: MutableMap<String, String> = mutableMapOf()
        for (item in CUSTOM_CONFIG.menuIdAndSiteType) {
            result[item.menuId] = item.type
        }
        return result
    }

    @Synchronized
    fun tryToUnLockSite(siteId: String) {
        val site = StoreSiteService.getStoreSiteById(siteId)
            ?: throw BusinessError("不存在库位【$siteId】!")

        if (!site.locked)
            throw BusinessError("无法解锁未锁定的库位【$siteId】!")

        try {
            StoreSiteService.unlockSiteIfLocked(siteId, "try to unlock site.")
        } catch (e: Exception) {
            logger.error("try to unlock site=$siteId failed!")
        }
    }

    @Synchronized
    fun tryToLockEmptyAndUnlockedSiteOfType(taskId: String, type: String): StoreSite {
        val sites = MongoDBManager.collection<StoreSite>().find(StoreSite::type eq type).toList()
        if (sites.isEmpty()) throw BusinessError("${TRY_NEXT_MARK}系统中没有类型为【$type】的库位")

        for (site in sites) {
            if (!site.filled && !site.locked && site.content.isBlank()) {
                val siteId = site.id
                try {
                    StoreSiteService.lockSiteIfNotLockAndEmpty(siteId, taskId, "try to lock site of type")
                    return site
                } catch (e: Exception) {
                    throw BusinessError("${TRY_NEXT_MARK}锁定库位【$siteId】失败，等待...")
                }
            }
        }
        throw BusinessError("${TRY_NEXT_MARK}库区【$type】中没有可用空库位，等待...")
    }

    @Synchronized
    fun tryToLockFilledAndUnlockedSiteOfType(taskId: String, type: String): StoreSite {
        val sites = MongoDBManager.collection<StoreSite>().find(StoreSite::type eq type).toList()
        if (sites.isEmpty()) throw BusinessError("系统中没有类型为【$type】的库位")

        val site = MongoDBManager.collection<StoreSite>()
            .findOne(Filters.and(StoreSite::type eq type, StoreSite::filled eq true, StoreSite::locked eq false))
            ?: throw BusinessError("库区【$type】中没有被占用且未锁定的库位，等待...")

        Phase2Utils.tryToLockSiteIfNotLocked(taskId, site.id)
        return site
    }

    @Synchronized
    fun setSiteContentIfEmptyAndUnlocked(siteId: String, newContent: String) {
        val site = StoreSiteService.getStoreSiteById(siteId)
            ?: throw BusinessError("无此库位【$siteId】，请重新输入正确的库位名称！")

        val content = site.content
        if (!content.isBlank()) throw BusinessError("无法操作已经堆放货物【$content】的库位【$siteId】！")
        if (site.locked) throw BusinessError("无法操作被锁定的库位【$siteId】！")

        if (plcSites.contains(site.id) && !site.filled)
            throw BusinessError("请在货物入库【${site.id}】后，再录入货物信息！")

        try {
            val remark = "PDA[更新库位]"
            StoreSiteService.setSiteContent(siteId, newContent, remark)
            if (!site.filled) StoreSiteService.changeSiteFilled(siteId, true, remark)
        } catch (e: Exception) {
            logger.error("${e.message}")
        }
    }

    fun checkSiteFilled(siteId: String): Boolean {
        val site = StoreSiteService.getStoreSiteById(siteId)
            ?: throw BusinessError("不存在库位【$siteId】！")

        return site.filled
    }

    fun getSiteTypeByMenuId(menuId: String): String {
        return getMenuIdAndSiteType()[menuId]
            ?: throw BusinessError("配置文件中没有 MenuId=$menuId，请检查配置文件！")
    }

    fun listUnfinishedTasksByTaskDef(def: String): List<RobotTask> {
        return MongoDBManager.collection<RobotTask>()
            .find(RobotTask::def eq def, RobotTask::state eq RobotTaskState.Created)
            .toList()
    }

    fun doResetResource() {
        // 将所有 在线接单 的机器人设置为在线不接单状态
        VehicleService.listVehicles().forEach { vehicle ->
            val vehicleName = vehicle.name
            try {
                if (vehicle.integrationLevel == Vehicle.IntegrationLevel.TO_BE_UTILIZED)
                    VehicleService.updateVehicleIntegrationLevel(vehicleName, Vehicle.IntegrationLevel.TO_BE_RESPECTED)
            } catch (e: Exception) {
                logger.error("update vehicle=$vehicleName to TO_BE_RESPECTED failed when reset because $e")
            }
        }

        val unfinished = MongoDBManager.collection<RobotTask>()
            .find(RobotTask::state eq RobotTaskState.Created).toList()
        unfinished.forEach { task ->
            val taskId = task.id
            // 撤销所有未结束的任务
            try {
                RobotTaskService.abortTask(taskId)
            } catch (e: Exception) {
                logger.error("abort task=$taskId failed when reset because $e")
            }

            val sites: MutableList<String> = mutableListOf()
            task.transports.forEach { transport -> transport.stages.forEach { sites.add(it.location) } }

            sites.filter { it.isNotBlank() }.forEach {
                try {
                    val site = StoreSiteService.getExistedStoreSiteById(it)
                    val remark = "reset resource and abort task=${taskId}."
                    // 解锁被任务锁定的库位
                    if (site.locked) StoreSiteService.changeSiteLocked(it, false, "", remark)
                    // 解除占用无码的库位
                    if (site.content.isBlank() && site.filled) StoreSiteService.changeSiteFilled(it, false, remark)
                    // 占用有码的库位
                    if (site.content.isNotBlank() && !site.filled) StoreSiteService.changeSiteFilled(it, true, remark)
                } catch (e: Exception) {
                    logger.error("operate site=$it failed when reset because $e")
                }
            }
        }
    }

    fun getUnfinishedTasksOrNullByTaskDef(taskDef: String): List<RobotTask>? {
        val list = listUnfinishedTasksByTaskDef(taskDef)
        return if (list.isEmpty()) null else list
    }

    fun existReturnEmptyTrayToStorageTaskOfSameFromSite(fromSiteId: String) {
        val tasks = getUnfinishedTasksOrNullByTaskDef(DEF_RETURN_EMPTY_TRAY_BACK_TO_STORAGE) ?: return
        val fromSiteIdLabel = "fromSiteId"
        tasks.forEach {
            if (fromSiteId == it.persistedVariables[fromSiteIdLabel])
                throw BusinessError("起点是【$fromSiteId】的任务【空料车运至产线】还未结束，请勿重复下单!")
        }
    }

    fun checkSiteFilledWithContent(siteId: String, content: String?) {
        val site = StoreSiteService.getStoreSiteById(siteId)
            ?: throw BusinessError("不存在库位【$siteId】!")

        val contentVaild = !content.isNullOrBlank()
        val text = if (contentVaild) "期望货物【$content】。" else ""

        if (!site.filled)
            throw BusinessError("起点库位【$siteId】不是被占用的状态！${text}")

        if (contentVaild && site.content != content)
            throw BusinessError("起点库位【$siteId】没有被货物【$content】占用！")
    }

    fun updatePersistedVariablesIntoDB(taskId: String, newPV: MutableMap<String, Any?>) {
        MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq taskId, set(
            RobotTask::persistedVariables setTo newPV
        ))
    }

    @Synchronized
    fun processTasksByCreatedOn(task: RobotTask) {
        // 筛选系统中还未完成的任务，并按创建时间升序排列
        val taskDefs =
            if (taskDefsForAGV01.contains(task.def)) taskDefsForAGV01 else taskDefsForAGV02
        val unfinishedTasks = MongoDBManager.collection<RobotTask>()
            .find(RobotTask::state eq RobotTaskState.Created, RobotTask::def `in` taskDefs)
            .ascendingSort(RobotTask::createdOn).toList()

        // 调用此方法时，不可能存在 (unfinishedTasks.isEmpty() == true) 的情况
        if (unfinishedTasks.isNotEmpty()) {
            val firstTask = unfinishedTasks.first()
            val taskId = task.id
            if (firstTask.id != taskId) {
                if (unfinishedTasks.size < 2) {
                    val taskDef = getRobotTaskDef(task.def)
                        ?: throw BusinessError("不存在柔性任务【${task.def}】")
                    throw BusinessError("业务类型为【${taskDef.transports.first().category}】的任务数量异常")
                }
                tryNextIfPrevUnfinishedTasksBlockedBySiteError(task, unfinishedTasks)
            }

            // 任务下发给调度之前需要设置运单的deadline为任务的创建时间，保证先创建的任务先执行的需求
            task.transports.forEach { it.deadline = task.createdOn }
        }
    }

    // 当之前可下发的任务由于操作库位失败而无法下发时，就尝试下发当前任务
    private fun tryNextIfPrevUnfinishedTasksBlockedBySiteError(task: RobotTask, unfinishedTasks: List<RobotTask>) {
        val prevSendAbleButUnSentTasks = mutableListOf<RobotTask>()
        val prevSentTasks = mutableListOf<RobotTask>()
        val unSendAbleTasksUntilCurr = mutableListOf<RobotTask>()
        for (unfinishedTask in unfinishedTasks) {
            if (unfinishedTask.id == task.id) break
            if (unfinishedTask.transports.first().stages.first().state == RobotStageState.Success) {
                if (unfinishedTask.transports.first().state >= RobotTransportState.Sent)
                    prevSentTasks.add(unfinishedTask)
                else prevSendAbleButUnSentTasks.add(unfinishedTask)
            } else unSendAbleTasksUntilCurr.add(unfinishedTask)
        }
        unSendAbleTasksUntilCurr.add(task)
        val prevTaskBlocked = prevSendAbleButUnSentTasks.any { blockedBySiteError(it.transports.first()) }

        if (prevTaskBlocked) {
            // 当前任务之前，存在由于操作库位失败而无法下发的任务
            if (unSendAbleTasksUntilCurr.first().id == task.id) {
                // 当前任务是第一条不满足下发条件的任务
                if (prevSentTasks.size > 0) {
                    // 如果存在已经下发给调度的任务，则当前任务不能再下发
                    throw BusinessError("有更早被创建的任务正在被处理！")
                } else {
                    // 如果还没有已经下发给调度的任务，但是可跳过的任务数量达到上限，则当前任务也不能下发
                    val limit = CUSTOM_CONFIG.limitOfSendAbleTasksForAgv02
                    val counts = prevSendAbleButUnSentTasks.size
                    if (limit in 1..counts)
                        throw BusinessError("可跳过的任务数量达到上限【$counts/$limit】,等待...")
                }
            } else {
                // 当前任务不是第一条不满足下发条件的任务，则当前任务不能下发
                throw BusinessError("有更早被创建的任务需要被处理！")
            }
        } else {
            // 当前任务之前，不存在由于操作库位失败而无法下发的任务，
            throw BusinessError("有更早被创建的任务需要被处理！")
        }
    }

    private fun blockedBySiteError(transport: RobotTransport): Boolean {
        for (stage in transport.stages) {
            if (stage.state == RobotStageState.Created && stage.blockReason.matches(Regex("^tryNext-.*$")))
                return true
        }
        return false
    }
}

data class CustomConfig(
    var extUrl: String = "http://localhost:7100/api/ext/sim/",
    var menuIdAndSiteType: List<MenuIdAndSiteType> = listOf(),
    var storeSiteTypesForClearContent: List<String> = listOf(),
    var storeSiteTypesForRecordContent: List<String> = listOf(),
    var plcDevices: Map<String, PlcConfig> = emptyMap(),
    var limitOfSendAbleTasksForAgv02: Int = 4
)

data class MenuIdAndSiteType(
    var menuId: String = "",
    var type: String = ""
)

data class ExecE2StationTaskIds(
    var current: String = "",
    var next: String = ""
) {
    fun reset() {
        current = ""
        next = ""
    }

    fun update(current: String, next: String) {
        this.current = current
        this.next = next
    }
}