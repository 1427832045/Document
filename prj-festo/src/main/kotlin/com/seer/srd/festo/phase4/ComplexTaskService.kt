package com.seer.srd.festo.phase4

import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.festo.ComplexTaskParams
import com.seer.srd.festo.ComplexTaskPvs
import com.seer.srd.festo.phase4.Component4.persistPersistedVariables
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskService.saveNewRobotTask
import com.seer.srd.robottask.RobotTaskState
import org.litote.kmongo.ascendingSort
import org.litote.kmongo.eq
import org.litote.kmongo.find

object ComplexTaskService {
    // 创建新的 Complex 任务
    @Synchronized
    fun modifyComplexTaskByTypeBeforeSave(type: String, params: ComplexTaskParams, task: RobotTask) {
        when (type) {
            EMPTY -> modifyNewEmptyTrayTask(task, params.toSiteId)
            FILLED -> modifyNewFilledTrayTask(task, params.fromSiteId)
            BOTH -> {
                // 任务请求中的 type 不能是 Both
                throw BusinessError("Bad type=$type, type must be Empty or Filled！")
            }
            else -> throw BusinessError("Undefined type=$type")
        }
    }

    // 创建新的空托盘任务
    private fun modifyNewEmptyTrayTask(task: RobotTask, toSiteId: String?) {
        if (toSiteId.isNullOrBlank()) throw BusinessError("未指定终点库位！")
        val pv = task.persistedVariables
        pv[TO_SITE_ID_EMPTY] = toSiteId
        // 不能存在未完成，且同产线库位的空托盘任务；不能存在未完成，且同产线库位的满托盘任务
        // （满托盘任务(type = Filled)，可能会导致空托盘任务(type = Empty)变成Complex任务(type = Both)）
        // 即 不能存在未完成，且同产线库位的Complex任务
        val complexTask = getAllTypesOfUnfinishedComplexTasks()
        if (complexTask.isNotEmpty()) {
            val selectedEmptyTasks = complexTask.filter {
                isExpectedType(listOf(BOTH, EMPTY), it)
                    && (it.persistedVariables[TO_SITE_ID_EMPTY]?.toString() == toSiteId)
            }
            if (selectedEmptyTasks.isNotEmpty()) {
                throw BusinessError("系统中存在未完成且同库位【$toSiteId】的空托盘任务，数量=${selectedEmptyTasks.size}！")
            }
            // todo: 需要在下发运单之前，需要完成如下操作：
            // - 设置此任务的终点库位，并锁定终点库位。（柔性任务的运单列表的结构可任意修改，再此处设置阶段工作站不太合理）
            // - 根据此任务的终点库位，选择合适的起点库位，并锁定。
        }
        saveNewRobotTask(task)
    }

    // 创建新的满托盘任务
    private fun modifyNewFilledTrayTask(task: RobotTask, fromSiteId: String?) {
        if (fromSiteId.isNullOrBlank()) throw BusinessError("未指定起点库位！")
        val pv = task.persistedVariables
        // 记录满托盘任务的起点
        pv[FROM_SITE_ID_FILLED] = fromSiteId
        // 获取所有未下发的 Complex 任务，并按照创建时间升序排列
        val complexTask = getAllTypesOfUnfinishedComplexTasks()
        // 如果没有 Complex 任务，则直接保存这条新创建的满托盘任，并开始执行。
        if (complexTask.isNotEmpty()) {
            // 如果存在未完成的，且起点库位相同的满托盘任务，则报错
            val selectedFilledTasks = complexTask.filter {
                isExpectedType(listOf(BOTH, FILLED), it)
                    && (it.persistedVariables[FROM_SITE_ID_FILLED]?.toString() == fromSiteId)
            }
            if (selectedFilledTasks.isNotEmpty())
                throw BusinessError("系统中存在未完成且同库位【$fromSiteId】的满托盘任务，数量=${selectedFilledTasks.size}！")

            // 将同产线且同组的空托盘任务升级成顺风车任务
            val selectedEmptyTasks = complexTask.filter {
                isExpectedType(listOf(EMPTY), it)
                    && checkEmptyTaskInGroup(it, fromSiteId)
            }
            if (selectedEmptyTasks.isNotEmpty()) {
                enableFilledTrayTaskOfExistedEmptyTrayTask(selectedEmptyTasks.first(), fromSiteId)
                return
            }
        }
        saveNewRobotTask(task)
        // 创建新的满托盘任务 ComplexTask.type = Filled
        // 需要再运单列表中做如下操作：跳过空托盘任务对应的运单。
    }

    // 修改已存在的空托盘任务，使其可以继续执行满托盘任务
    private fun enableFilledTrayTaskOfExistedEmptyTrayTask(task: RobotTask, fromSiteIdFilled: String) {
        // val pv = task.persistedVariables
        // pv[TYPE] = BOTH
        // pv[FROM_SITE_ID_FILLED] = fromSiteIdFilled
        persistPersistedVariables(task.id, ComplexTaskPvs(type = BOTH, fromSiteIdFilled = fromSiteIdFilled))
    }

    fun getAllTypesOfUnfinishedComplexTasks(): List<RobotTask> {
        return MongoDBManager.collection<RobotTask>()
            .find(RobotTask::def eq TASK_DEF_COMPLEX, RobotTask::state eq RobotTaskState.Created)
            .ascendingSort(RobotTask::createdOn)
            .filter { isExpectedType(emptyList(), it) }
    }

    fun isExpectedType(types: List<String>, task: RobotTask): Boolean {
        if (types.isEmpty()) return true
        val recordType = getTypeOfComplexTask(task) ?: return false
        return types.contains(recordType)
    }

    fun checkEmptyTaskInGroup(emptyTask: RobotTask, siteId: String): Boolean {
        val recordedToSiteId = emptyTask.persistedVariables[TO_SITE_ID_EMPTY]?.toString() ?: return false
        return Component4.getGroupOfVSOBySiteId(recordedToSiteId) == Component4.getGroupOfVSOBySiteId(siteId)
    }

    fun getTypeOfComplexTask(task: RobotTask): String? {
        if (task.def != TASK_DEF_COMPLEX) return null
        return task.persistedVariables[TYPE]?.toString() ?: ""
    }

    fun execEmptyTask(type: String?): Boolean {
        return listOf(BOTH, EMPTY).contains(type)
    }

    fun execFilledTask(type: String?): Boolean {
        return listOf(BOTH, FILLED).contains(type)
    }

    fun getSentOfComplexTask(task: RobotTask): String? {
        if (task.def != TASK_DEF_COMPLEX) return null
        return task.persistedVariables[SENT]?.toString()
    }

    fun checkTaskSent(task: RobotTask): Boolean {
        return getSentOfComplexTask(task) == SENT
    }

    fun checkTaskUnsent(task: RobotTask): Boolean {
        return getSentOfComplexTask(task) != SENT
    }
}