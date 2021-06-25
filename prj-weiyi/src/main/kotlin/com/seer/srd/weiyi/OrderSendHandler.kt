package com.seer.srd.weiyi

import com.mongodb.client.model.Sorts
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.route.service.VehicleService
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.vehicle.Vehicle
import com.seer.srd.weiyi.SentTaskManager.getUnfinishedSentTaskById
import com.seer.srd.weiyi.SentTaskManager.listUnfinishedSentTasksAscByCreatedOn
import com.seer.srd.weiyi.SentTaskManager.updateSentTask
import io.javalin.http.Context
import org.litote.kmongo.*
import org.opentcs.data.order.TransportOrder
import org.opentcs.data.order.TransportOrderState
import org.slf4j.LoggerFactory
import java.time.Instant

object OrderSendHandler {
    private const val taskDefProductIn = "TaskDefProductIn"
    private const val taskDefProductOut = "TaskDefProductOut"
    private val bufCount = getBufCount()

    private val logger = LoggerFactory.getLogger(ExtHandlers::class.java)

    private val unDispensableTaskSign = listOf("Park", "Recharge", "Rech")

    @Volatile
    private var sentSeqIdList: MutableList<String> = mutableListOf()

    private val availableStates: List<Vehicle.State> = listOf(Vehicle.State.CHARGING)

    private val finalStateOfOrder: List<TransportOrderState> = listOf(
        TransportOrderState.FAILED, TransportOrderState.FINISHED, TransportOrderState.UNROUTABLE
    )

    private val notFinalStateOfOrder: List<TransportOrderState> = listOf(
        TransportOrderState.WITHDRAWN, TransportOrderState.BEING_PROCESSED, TransportOrderState.DISPATCHABLE,
        TransportOrderState.ACTIVE, TransportOrderState.RAW
    )

    init {
        logger.info("initialize not completed but sent seq list: $sentSeqIdList")
        logger.debug("bufCount=$bufCount")
    }

    private fun getBufCount(): Int {
        StoreSiteService.loadStoreSites()
        return StoreSiteService.listStoreSites().filter { it.type == "K" }.size
    }

    /** 判断是否存在未完成的高优先级的任务  */
    @Synchronized
    fun sendTaskIfPriorityAndVehicleNumPermitted(task: RobotTask) {
        val priority = task.priority
        val currentTaskId = task.id

        executingTasksReachLimitation(task.def)

        val existEmptyBufSite = allBufferSitesFilled(bufCount)

        // 缓存区都被占用，无法下发出库任务
        logger.info("def=${task.def}, str=$taskDefProductOut, bool=${task.def == taskDefProductOut}")
        if (task.def == taskDefProductOut && !existEmptyBufSite)
            throw BusinessError("所有缓存库位都已经被分配，无法下发出库任务。")

        existHigherPriorityTaskButNotSent(task, existEmptyBufSite)

        // 是否有能立即执行即将下发的任务的机器人
        vehicleAvailable(listUnfinishedSentTasksAscByCreatedOn(), currentTaskId)

        existTaskCreatedEarlierButNotSent(currentTaskId, priority)
    }

    fun existHigherPriorityTaskButNotSent(task: RobotTask, existEmptyBufSite: Boolean) {
        val unsentHigherPriorityTaskIds = getTaskIdListByPriority(task.priority, true)

        if (unsentHigherPriorityTaskIds.isNotEmpty()) {
            // 如果有4辆车在执行出库任务，则尝试下发一条低优先级的入库任务
            if (task.def == taskDefProductIn && !existEmptyBufSite) {
                logger.info("尝试下发入库任务")
                return
            }

            val errMsg1 = "未下发的任务中存在更高优先级的运单序列"
            logger.error("current task=${task.id} $errMsg1 $unsentHigherPriorityTaskIds")
            throw BusinessError(errMsg1)
        }
    }

    fun existTaskCreatedEarlierButNotSent(taskId: String, priority: Int) {
        val unsentPeerPriorityTaskIds = getTaskIdListByPriority(priority, false)
        if (unsentPeerPriorityTaskIds.isNotEmpty()) {
            val firstOne = unsentPeerPriorityTaskIds.first()
            if (firstOne != taskId) {
                val errMsg = "存在更早被创建且未下发的同优先级的运单序列 "
                logger.error("current task=$taskId $errMsg" +
                    "${unsentPeerPriorityTaskIds.subList(0, unsentPeerPriorityTaskIds.indexOf(taskId))}")
                throw BusinessError(errMsg)
            }
        }
    }

    fun getTaskIdListByPriority(priority: Int, getHigherPriority: Boolean): List<String> {
        val c = MongoDBManager.collection<RobotTask>()
        val notFinalStateTasks =
            if (getHigherPriority) c.find(RobotTask::priority gt priority, RobotTask::state eq RobotTaskState.Created)
            else c.find(RobotTask::priority eq priority, RobotTask::state eq RobotTaskState.Created)

        val notFinalStateTaskIds = notFinalStateTasks
            .sort(Sorts.ascending("createdOn")).toMutableList().map { it.id }.toMutableList()

        val executingTaskIds = MongoDBManager.collection<SentTask>()
            .find(SentTask::finished eq false).sort(Sorts.ascending("createdOn"))
            .toMutableList().map { it.taskId }

        // 筛选出未下发的任务ID的集合
        executingTaskIds.forEach { etId ->
            if (notFinalStateTaskIds.contains(etId)) notFinalStateTaskIds.remove(etId)
        }
        return notFinalStateTaskIds
    }

    /** 判断系统中是否存在立即可用的机器人 */
    fun vehicleAvailable(unfinishedSentTasks: List<SentTask>, taskId: String) {
        // 统计系统中非终态，且运单序列不同的运单数量 num
        val notFinalStateOrder = MongoDBManager.collection<TransportOrder>()
            .find(TransportOrder::state nin finalStateOfOrder, TransportOrder::isDispensable eq false).toMutableList()

        // 排除被运单序列占用的机器人
        val vehicleIds = getAvailableVehicleIds()
        val vehicleNum = vehicleIds.keys.size
        val seqIdsOfUnfinishedSentTask: MutableList<String> = mutableListOf()
        unfinishedSentTasks.forEach {
            seqIdsOfUnfinishedSentTask.add(it.seqId)
        }

        logger.info("seqIdsOfUnfinishedSentTask=$seqIdsOfUnfinishedSentTask")
        var executingOrderNum = notFinalStateOrder.size // 运单数量
        val orderIds: MutableList<String> = mutableListOf()
        var size = unfinishedSentTasks.size // 任务数量
        notFinalStateOrder.forEach { order ->
            val seqId = order.wrappingSequence ?: order.name
            // 通过运单序列下发的运单需要排除，防止重复计算
            logger.info("seqId of notFinalStateOrder=${seqId}")
            // 正在执行的非必要任务可以直接被终止 Park & Recharge -> isDispensable = true
            // 排除未下发，且未分配机器人的运单
            if (seqIdsOfUnfinishedSentTask.contains(seqId) && !order.processingVehicle.isNullOrBlank()) {
                executingOrderNum-- // 去掉被重复计算的运单
                size--  // 运单序列中不可能同时存在多条正在执行的运单
            } else {
                orderIds.add(seqId)
            }
        }

        logger.info("getAvailableVehicleNum=$vehicleNum, executingOrderNum=$executingOrderNum, sentTaskIdCount=$size")
        if (vehicleNum == 0) {
            val errMsg1 = "没有可用的机器人"
            logger.error("current task=$taskId $errMsg1")
            throw BusinessError(errMsg1)
        }
        if (vehicleNum <= executingOrderNum + size) {
            val errMsg2 = "可用机器人数量[$vehicleNum]不多于执行中的任务数量[$executingOrderNum] + [$size]"
            logger.error("current task=$taskId $errMsg2, orders=$orderIds")
            throw BusinessError(errMsg2)
        }
    }

    fun getAvailableVehicleIds(): Map<String, Vehicle> {
        val vehicles: MutableMap<String, Vehicle> = mutableMapOf()
        VehicleService.listVehicles().forEach { vehicle ->
            val state = vehicle.state
            val procState = vehicle.procState
            // IDEL 但是 正在执行任务的机器人，不在此处过滤
            if (vehicle.integrationLevel == Vehicle.IntegrationLevel.TO_BE_UTILIZED && vehicle.currentPosition != null) {
                val vehicleId = vehicle.name
                if (state == Vehicle.State.CHARGING) {
                    if (vehicle.energyLevel > vehicle.energyLevelCritical) vehicles[vehicleId] = vehicle
                } else {
                    if (procState == Vehicle.ProcState.IDLE && state == Vehicle.State.IDLE) vehicles[vehicleId] = vehicle
                    else {
                        if ((procState == Vehicle.ProcState.PROCESSING_ORDER || state == Vehicle.State.EXECUTING) &&
                            unDispensableTaskSign.contains(vehicle.transportOrder?.split("-")?.first())
                        ) vehicles[vehicleId] = vehicle
                    }
                }
            }
        }
        return vehicles
    }

    fun recordOrderNameIntoExecutingTask(taskId: String, orderId: String, remark: String) {
        val c = MongoDBManager.collection<SentTask>()
        val record = c.findOne(SentTask::taskId eq taskId)
        if (record == null)
            logger.error("executing task=[$taskId] has been recorded.")
        else {
            logger.info("record executing task=[$taskId] success.")
            val orderIds = record.orderIds.toMutableList()
            if (!orderIds.contains(orderId)) {
                orderIds.add(orderId)
                updateSentTask(SentTask(modifiedOn = Instant.now(), orderIds = orderIds, remark = remark))
            }
        }
    }

    fun updateExecutingTask() {
        // 更新机器人名称、运单名称、是否完成
        try {
            val unfinishedMap: MutableMap<String, SentTask> = mutableMapOf()
            val taskIds = listUnfinishedSentTasksAscByCreatedOn().map {
                unfinishedMap[it.taskId] = it
                it.taskId
            }
            if (taskIds.isEmpty()) return

            val tasks = MongoDBManager.collection<RobotTask>().find(RobotTask::id `in` taskIds)
            // mark deleted SentTask to finished
            val taskIds2 = tasks.map { it.id }.toMutableList()
            taskIds.forEach {
                if (taskIds2.indexOf(it) < 0) markExecutingTaskFinished(it, "mark it finished cause it`s deleted")
            }

            for (task in tasks) {
                val taskId = task.id
                val currentSentTask = unfinishedMap[taskId]
                val finished = (task.state >= RobotTaskState.Success)
                if (currentSentTask != null) {
                    val orderIds = currentSentTask.orderIds.toMutableList()
                    var vehicleId = currentSentTask.vehicleId
                    for (transport in task.transports) {
                        vehicleId = vehicleId ?: transport.processingRobot
                        val orderId = transport.routeOrderName
                        if (orderId.isNotEmpty() && !orderIds.contains(orderId)) orderIds.add(orderId)
                    }
                    updateSentTask(SentTask(taskId = taskId, vehicleId = vehicleId, orderIds = orderIds,
                        finished = finished, modifiedOn = Instant.now(), remark = "update by GlobalTimer"))

                } else { // task.id 已经被删除，标记为终态，否则会影响下发任务
                    updateSentTask(SentTask(taskId = taskId, finished = true, modifiedOn = Instant.now(),
                        remark = "no record In RobotTask"))
                }
            }
        } catch (e: Exception) {
            logger.error("updateExecutingTask error: $e")
        }
    }

    fun markExecutingTaskFinished(taskId: String, remark: String) {
        logger.info("mark sentTask.taskId=$taskId to finished for $remark")
        getUnfinishedSentTaskById(taskId)
        updateSentTask(SentTask(taskId = taskId, finished = true, modifiedOn = Instant.now(), remark = remark))
    }

    fun markExecutingTaskFinished(ctx: Context) {
        val taskId = ctx.pathParam("taskId")
        val remark = ctx.queryParam("remark") ?: "by API"
        markExecutingTaskFinished(taskId, remark)
    }

    fun allBufferSitesFilled(bufCount: Int): Boolean {
        val unfinishedSentTasks = listUnfinishedSentTasksAscByCreatedOn()
        var filled = 0
        for (st in unfinishedSentTasks) {
            if (st.def == taskDefProductOut) filled++
        }

        // 缓存位有空闲时，就不能下发入库任务
        if (filled < bufCount) {
            logger.info("已下发的出库任务($filled) < 缓存库位数量($bufCount)，无法下发入库任务！")
            return true
        }
        return false
    }

    // 限制指定类型的任务的最大执行数量
    private fun executingTasksReachLimitation(taskDef: String) {
        val sentTasks = MongoDBManager.collection<SentTask>()
            .find(SentTask::def eq taskDef, SentTask::finished eq false).toMutableList()

        val size = sentTasks.size
        when (taskDef) {
            // 入库任务最多能执行3条
            "TaskDefProductIn" -> {
                val inMax = CUSTOM_CONFIG.maxExecNumOfProductIn
                if (size >= inMax) throw BusinessError("正在执行的入库任务数量达到上限（$inMax）！")
            }
            // 出库任务最多执行5条
            "TaskDefProductOut" -> {
                val outMax = CUSTOM_CONFIG.maxExecNumOfProductOut
                if (size >= outMax) throw BusinessError("正在执行的出库任务数量达到上限（${outMax}）！")
            }
            else -> {
                // do nothing
            }
        }
    }
}