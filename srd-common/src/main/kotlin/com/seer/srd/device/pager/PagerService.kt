package com.seer.srd.device.pager

import com.seer.srd.BusinessError
import com.seer.srd.CONFIG
import com.seer.srd.RobotTaskListExtraColumn
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.scheduler.GlobalTimer
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

const val FIELD_PATH_PAGER = "persistedVariables.pager"
const val FIELD_PAGER = "pager"

object PagerService {

    private val logger = LoggerFactory.getLogger(PagerService::class.java)

    private val pagers: MutableMap<String, PagerManager> = ConcurrentHashMap()

    fun init() {
        val cps = CONFIG.pagers
        if (cps.isEmpty()) return

        showPagerOnRobotTaskList()

        cps.forEach { config -> pagers[config.name] = PagerManager(config) }

        if (pagers.isNotEmpty()) {
            // 每秒更新一次呼叫器的状态。
            GlobalTimer.executor.scheduleAtFixedRate(
                this::updatePagerSignalWithUnfinishedTask,
                2000,
                1000,
                TimeUnit.MILLISECONDS
            )
        }
    }

    fun getPagerByName(name: String): PagerManager {
        return pagers[name] ?: throw BusinessError("Cannot find pager=$name !")
    }

    fun getPagerByTaskId(taskId: String): PagerManager {
        val managers = pagers.values.filter { it.getTaskId() == taskId }
        return when (val size = managers.size) {
            0 -> throw BusinessError("No pager holds task=$taskId !")
            1 -> managers.first()
            else -> throw BusinessError("There are $size pagers hold task=$taskId !")
        }
    }

    fun listPagerDetails(): List<PagerModel> {
        return pagers.map { it.value.getModel() }
    }

    fun resetPagerByName(name: String) {
        val pager = pagers[name] ?: throw BusinessError("No pager named $name !")
        pager.reset()
    }

    private fun showPagerOnRobotTaskList() {
        val ec = CONFIG.robotTaskListExtraColumns.toMutableList()
        if (!ec.map { it.fieldPath }.contains(FIELD_PATH_PAGER)) {
            ec.add(RobotTaskListExtraColumn("呼叫器", FIELD_PATH_PAGER))
            CONFIG.robotTaskListExtraColumns = ec
        }
    }

    private fun updatePagerSignalWithUnfinishedTask() {
        val pagersWithTask = pagers.values.filter { it.getTaskId().isNotBlank() }
        pagersWithTask.forEach {
            val taskId = it.getTaskId()
            try {
                val unfinishedTask = MongoDBManager.collection<RobotTask>()
                    .findOne(RobotTask::id eq taskId, RobotTask::state eq RobotTaskState.Created)
                    ?: return@forEach
                it.updateSignalValueByTask(unfinishedTask)
                it.tryToMarkVehicleError()
            } catch (e: Exception) {
                logger.info("Update signal of pager[${it.config.name}] by task=$taskId failed: ", e)
            }
        }
    }
}