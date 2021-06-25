package com.seer.srd.siemensSH.customEventBus

import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.siemensSH.MaterialProductMappingService.markRecordsUsedByMaterials
import com.seer.srd.siemensSH.common.DEF_E_TO_STATION
import org.slf4j.LoggerFactory

object CustomEventBus {

    private val logger = LoggerFactory.getLogger(CustomEventBus::class.java)

    fun onRobotTaskFinished(task: RobotTask) {
        val taskState = task.state
        // TaskDefEToStation任务异常结束时，将 product-material-mapping 里面对应的记录设置为 processed=false
        if (task.def == DEF_E_TO_STATION
            && (taskState == RobotTaskState.Aborted || taskState == RobotTaskState.Failed)) {
            val matCode = task.persistedVariables["content"]
            if (matCode != null) {
                val message = "task=${task.id} is $taskState"
                logger.debug("$message, reset material to processed=false.")
                markRecordsUsedByMaterials(
                    listOf(matCode as String), false, "[customEventBus] $message")
            }
        }
    }
}