package com.seer.srd.robottask

import com.seer.srd.BusinessError
import com.seer.srd.TaskAbortedError
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.eventbus.EventBus.onRobotTaskCreated
import com.seer.srd.eventbus.EventBus.onRobotTaskFinished
import com.seer.srd.eventbus.EventBus.onRobotTaskRemoved
import com.seer.srd.eventbus.EventBus.onRobotTaskUpdated
import com.seer.srd.eventlog.EventLogLevel
import com.seer.srd.eventlog.SystemEvent
import com.seer.srd.eventlog.recordSystemEventLog
import com.seer.srd.route.service.OrderSequenceIOService.withdrawalSequenceByName
import com.seer.srd.route.service.TransportOrderIOService.withdrawTransportOrder
import com.seer.srd.storesite.StoreSiteService.releaseOwnedSites
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.collections.toList

object RobotTaskService {
    
    private val logger = LoggerFactory.getLogger(RobotTaskService::class.java)
    
    private val tasksCache: MutableMap<String, RobotTask> = ConcurrentHashMap()
    
    /** 重启后，自动执行上次未完成的任务 */
    fun startNotFinishedRobotTasks() {
        val tasks = collection<RobotTask>().find(RobotTask::state lt RobotTaskState.Success).toList()
        logger.info("${tasks.size} not finished robot tasks since last boot")
        for (task in tasks) {
            tasksCache[task.id] = task
            processTaskSilent(task)
        }
    }
    
    // 标记为停止处理的任务
    // 为了让内存中正在处理的任务知道自己已被终止
    private val abortingTasks: MutableSet<String> = CopyOnWriteArraySet()
    
    /**
     * Clean and persist the new RobotTask. So not call this function if further codes may fail.
     */
    fun saveNewRobotTask(task: RobotTask) {
        logger.info("Save new robot task ${task.id} [${task.def}]")
        
        tasksCache[task.id] = task
        
        collection<RobotTask>().insertOne(task)
        
        recordSystemEventLog("RobotTask", EventLogLevel.Info, SystemEvent.RobotTaskCreated, "${task.id} [${task.def}]")
        onRobotTaskCreated(task)
        
        processTaskSilent(task) // async
    }
    
    fun markTaskSuccess(task: RobotTask) {
        logger.info("Task success ${task.id} [${task.def}]")
        
        recordSystemEventLog("RobotTask", EventLogLevel.Info, SystemEvent.RobotTaskSuccess, "${task.id} [${task.def}]")
        
        val finished = Instant.now()
        task.finishedOn = finished
        task.duration = Duration.between(task.createdOn, finished).toMillis()
        task.state = RobotTaskState.Success
        
        collection<RobotTask>().updateOne(
            RobotTask::id eq task.id,
            set(
                RobotTask::finishedOn setTo task.finishedOn,
                RobotTask::duration setTo task.duration,
                RobotTask::state setTo task.state
            )
        )
        
        tasksCache.remove(task.id)
        
        onRobotTaskFinished(task)
    }
    
    fun markTaskFailed(task: RobotTask) {
        logger.error("Task failed ${task.id} [${task.def}]")
        
        recordSystemEventLog("RobotTask", EventLogLevel.Error, SystemEvent.RobotTaskFailed, "${task.id} [${task.def}]")
        
        val finished = Instant.now()
        task.finishedOn = finished
        task.duration = Duration.between(task.createdOn, finished).toMillis()
        task.state = RobotTaskState.Failed
        
        collection<RobotTask>().updateOne(
            RobotTask::id eq task.id,
            set(
                RobotTask::finishedOn setTo task.finishedOn,
                RobotTask::duration setTo task.duration,
                RobotTask::state setTo task.state
            )
        )
        
        tasksCache.remove(task.id)
        
        onRobotTaskFinished(task)

        releaseOwnedSites(task.id, "FromTaskFailed")
    }
    
    fun abortTask(taskId: String, immediate: Boolean = true, disableVehicle: Boolean = true) {
        val task = tasksCache[taskId] ?: throw BusinessError("Illegal operation! Task $taskId is final state!!")
        task.state = RobotTaskState.Aborted
        task.finishedOn = Instant.now()
        task.modifiedOn = Instant.now()
        
        collection<RobotTask>().updateOne(
            RobotTask::id eq task.id,
            set(
                RobotTask::state setTo RobotTaskState.Aborted,
                RobotTask::finishedOn setTo task.finishedOn,
                RobotTask::modifiedOn setTo task.modifiedOn
            )
        )
        
        tasksCache.remove(task.id)
        
        doAbortTask(task.id, immediate, disableVehicle)
        
        onRobotTaskFinished(task)
    }
    
    fun throwIfTaskAborted(taskId: String) {
        if (abortingTasks.contains(taskId)) throw TaskAbortedError("")
    }
    
    // 删除也会调用此方法，此方法只负责终止，不负责标记状态
    private fun doAbortTask(taskId: String, immediate: Boolean = true, disableVehicle: Boolean = true) {
        abortingTasks.add(taskId)
        withdrawTransportsOfTask(taskId, immediate, disableVehicle)
    }
    
    /** 撤销此任务关联的运单 */
    private fun withdrawTransportsOfTask(taskId: String, immediate: Boolean, disableVehicle: Boolean) {
        // TODO 可能需要同步：确保任务确实停下后
        
        val c = collection<RobotTask>()
        val task = c.findOne(RobotTask::id eq taskId) ?: return
        val transports = task.transports
        val orderSequenceNames: MutableSet<String> = HashSet()
        for (transport in transports) {
            try {
                val seqId = transport.seqId
                if (!seqId.isNullOrBlank()) orderSequenceNames += seqId
                if (transport.routeOrderName.isBlank()) {
                    logger.info("Cancel order, but transport not sent, ${taskId}/${transports}")
                    continue
                }
                if (transport.state >= RobotTransportState.Success) {
                    continue
                }
                withdrawTransportOrder(transport.routeOrderName, immediate, disableVehicle)
            } catch (err: Exception) {
                logger.error("Withdraw route order", err)
            }
        }
        for (seqName in orderSequenceNames) {
            try {
                withdrawalSequenceByName(seqName, immediate, disableVehicle)
            } catch (e: Exception) {
                logger.error("Withdraw order sequence", e)
            }
        }
    }
    
    fun removeTask(taskId: String) {
        tasksCache.remove(taskId)
        
        collection<RobotTask>().deleteOne(RobotTask::id eq taskId)
        collection<RobotTransport>().deleteMany(RobotTransport::taskId eq taskId)
        
        onRobotTaskRemoved()
        
        doAbortTask(taskId)
    }
    
    private fun markTaskModified(task: RobotTask) {
        task.modifiedOn = Instant.now()
        
        collection<RobotTask>().updateOne(RobotTask::id eq task.id, set(RobotTask::modifiedOn setTo task.modifiedOn))
        
        onRobotTaskUpdated(task)
    }
    
    fun updateRobotTaskPriority(taskId: String, priority: Int) {
        val task = tasksCache[taskId] ?: throw BusinessError("Illegal operation! Task $taskId is final state!!")
        task.priority = priority
        task.modifiedOn = Instant.now()
        
        collection<RobotTask>().updateOne(
            RobotTask::id eq task.id,
            set(RobotTask::priority setTo priority, RobotTask::modifiedOn setTo task.modifiedOn)
        )
        
        adjustSentTransportDeadlineForPriority(task, priority)
    }
    
    fun updateTransportSeqStartSentOn(sentOn: Instant, transport: RobotTransport, task: RobotTask) {
        transport.seqStartSentOn = sentOn
        val c = collection<RobotTask>()
        c.updateOne(RobotTask::id eq task.id, set(RobotTask::transports setTo task.transports))
        
        markTaskModified(task)
    }
    
    fun updateTransportSeqId(seqId: String?, transport: RobotTransport, task: RobotTask): String {
        val id = seqId ?: ObjectId().toHexString()
        transport.seqId = id
        
        collection<RobotTask>().updateOne(RobotTask::id eq task.id, set(RobotTask::transports setTo task.transports))
        
        markTaskModified(task)
        
        return id
    }
    
    fun ensureRouteOrderName(transport: RobotTransport, task: RobotTask) {
        if (!transport.routeOrderName.isBlank()) return
        transport.routeOrderName = ObjectId().toHexString()
        
        collection<RobotTask>().updateOne(RobotTask::id eq task.id, set(RobotTask::transports setTo task.transports))
        
        // 不更新任务
    }
    
    fun updateTransportState(state: Int, transport: RobotTransport, task: RobotTask) {
        transport.state = state
        
        collection<RobotTask>().updateOne(RobotTask::id eq task.id, set(RobotTask::transports setTo task.transports))
        
        markTaskModified(task)
    }
    
    fun updateTransportStateToFinal(state: Int, reason: String, transport: RobotTransport, task: RobotTask) {
        transport.state = state
        transport.failReason = reason
        transport.finishedOn = Instant.now()
        
        collection<RobotTask>().updateOne(RobotTask::id eq task.id, set(RobotTask::transports setTo task.transports))
        
        markTaskModified(task)
    }
    
    fun updateTransportProcessingRobot(robot: String, transport: RobotTransport, task: RobotTask) {
        transport.processingRobot = robot
        transport.processingRobotAssignedOn = Instant.now()
        
        collection<RobotTask>().updateOne(RobotTask::id eq task.id, set(RobotTask::transports setTo task.transports))
        
        markTaskModified(task)
    }
    
    fun updateStageBlockReason(reason: String, stage: RobotStage, task: RobotTask) {
        stage.blockReason = reason
        
        collection<RobotTask>().updateOne(RobotTask::id eq task.id, set(RobotTask::transports setTo task.transports))
        
        // 不更新任务
    }
    
    fun updateStageState(state: Int, stage: RobotStage, task: RobotTask) {
        stage.state = state
        
        collection<RobotTask>().updateOne(RobotTask::id eq task.id, set(RobotTask::transports setTo task.transports))
        
        markTaskModified(task)
    }
    
    fun markStageSuccess(timeCost: Long, stage: RobotStage, task: RobotTask) {
        stage.state = RobotStageState.Success
        stage.timeCost = timeCost
        stage.blockReason = ""
        stage.finishedOn = Instant.now()
        
        // 要更新全部运单，因为阶段可能修改其他运单
        collection<RobotTask>().updateOne(RobotTask::id eq task.id, set(RobotTask::transports setTo task.transports))
        
        markTaskModified(task)
    }
    
    fun updateStageRouteState(state: String, stage: RobotStage, task: RobotTask) {
        stage.routeState = state
        
        collection<RobotTask>().updateOne(RobotTask::id eq task.id, set(RobotTask::transports setTo task.transports))
    }
    
}