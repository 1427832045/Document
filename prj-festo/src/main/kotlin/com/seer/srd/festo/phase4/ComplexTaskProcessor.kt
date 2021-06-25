package com.seer.srd.festo.phase4

import com.seer.srd.BusinessError
import com.seer.srd.festo.phase4.ComplexTaskService.checkTaskSent
import com.seer.srd.festo.phase4.ComplexTaskService.checkTaskUnsent
import com.seer.srd.festo.phase4.ComplexTaskService.getAllTypesOfUnfinishedComplexTasks
import com.seer.srd.festo.phase4.ComplexTaskService.getTypeOfComplexTask
import com.seer.srd.festo.phase4.Component4.limit
import com.seer.srd.robottask.RobotTask
import org.slf4j.LoggerFactory

object ComplexTaskProcessor {
    private val logger = LoggerFactory.getLogger(ComplexTaskProcessor::class.java)

    private fun logErrorAndThrowIt(logMsg: String?, throwMsg: String?) {
        if (!logMsg.isNullOrBlank()) logger.error(logMsg)
        if (!throwMsg.isNullOrBlank()) throw BusinessError(throwMsg)
    }

    @Synchronized
    fun process(task: RobotTask) {
        processTask(task)
    }

    private fun processTask(task: RobotTask) {
        // priority: Type.Both > Type.Filled > Type.Empty
        val taskId = task.id
        try {
            val unfinishedTasks = getAllTypesOfUnfinishedComplexTasks()
            // 需要从数据库中获取当前任务的实时数据type，否则任务无法正常执行。
            val currentTaskFromDB = unfinishedTasks.find { it.id == taskId }
                ?: throw BusinessError("数据库中未记录任务当前任务【$taskId】")
            val currentType = getTypeOfComplexTask(currentTaskFromDB)
                ?: throw BusinessError("当前任务不属于 TaskDefComplex ...")
            if (currentType.isBlank())
                throw BusinessError("当前任务(ComplexTask)未记录类型，无法执行...")
            val unfinishedAndSentTasks = unfinishedTasks.filter { checkTaskSent(it) }
            when (currentType) {
                BOTH -> {
                    // 最高优先级任务，可以直接进入下发的逻辑
                }
                FILLED -> {
                    // 第二优先的任务，没有未下发的Both任务时，可以进入下发逻辑
                    // 存在未下发的Both任务时，不下发
                    val unsentBothTasks = getUnsentComplexTaskByType(unfinishedTasks, BOTH)
                    if (unsentBothTasks.isNotEmpty())
                        throw BusinessError("有更高优先级的顺风车任务【${unsentBothTasks.first().id}】待下发...")
                }
                EMPTY -> {
                    // 第三优先的任务，没有未下发的Both和Filled任务时，可以进入下发逻辑
                    // 存在未下发的Both或Filled任务时，不下发
                    val unsentBothTasks = getUnsentComplexTaskByType(unfinishedTasks, BOTH)
                    if (unsentBothTasks.isNotEmpty())
                        throw BusinessError("有更高优先级的顺风车任务【${unsentBothTasks.first().id}】待下发...")

                    val unsentFilledTasks = getUnsentComplexTaskByType(unfinishedTasks, FILLED)
                    if (unsentFilledTasks.isNotEmpty())
                        throw BusinessError("有更高优先级的满托盘任务【${unsentFilledTasks.first().id}】待下发...")
                }
                else -> throw BusinessError("无法识别的type=$currentType !")
            }
            // 如果已下发的Complex任务超过了指定数量，就会有错误提示
            unfinishedTasksSendable(unfinishedAndSentTasks)

        } catch (e: Exception) {
            val message = e.message
            logErrorAndThrowIt("$taskId $message", message)
        }
    }

    private fun getUnsentComplexTaskByType(source: List<RobotTask>, type: String): List<RobotTask> {
        if (!listOf(BOTH, FILLED, EMPTY).contains(type))
            throw BusinessError("无法识别的type=$type,期望值为 Both | Filled | Empty ...")
        return source.filter {
            getTypeOfComplexTask(it) == type && checkTaskUnsent(it)
        }
    }

    private fun unfinishedTasksSendable(unfinishedAndSentTasks: List<RobotTask>) {
        if (unfinishedAndSentTasks.size < limit) return // 已下发的任务数量未达到上限，直接下发
        else throw BusinessError("超过可下发的任务数量上限(四期)【$limit】")
    }

}