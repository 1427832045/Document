package com.seer.srd.jinfeng

import com.mongodb.client.model.Sorts
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.domain.PagingResult
import com.seer.srd.http.Handlers
import com.seer.srd.http.ReqMeta
import com.seer.srd.http.getPageNo
import com.seer.srd.http.getPageSize
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskService
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.util.loadConfig
import io.javalin.http.Context
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val taskTypeConstConfig = loadConfig("srd-config.yaml", TaskTypeConstConfig::class) ?: TaskTypeConstConfig()
private val customConfig = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

object Handle {
    private val logger = LoggerFactory.getLogger(Handle::class.java)

    fun taskList(ctx: Context) {
        val pageNo = getPageNo(ctx)
        val pageSize = getPageSize(ctx)
//        val oneHourBefore = Instant.ofEpochSecond((Instant.now().toEpochMilli() - 3600 * 1000) / 1000)
        val oneHourBefore = Instant.now().minus(1, ChronoUnit.HOURS)
        val c = MongoDBManager.collection<JinFengTask>()
        val filter = or(
            JinFengTask::state `in` listOf(ErpBodyState.created, ErpBodyState.started),
            and(JinFengTask::state eq ErpBodyState.finished, JinFengTask::modifiedOn gt oneHourBefore)
        )
        val total = c.countDocuments(filter)
        val page = c.find(filter)
            .limit(pageSize).skip((pageNo - 1) * pageSize)
            .sort(Sorts.descending("modifiedOn")).toMutableList()
        ctx.json(PagingResult(total, page, pageNo, pageSize))
    }

    fun agvList(ctx: Context) {
        val dateStr = ctx.pathParam("dateStr")
        if (dateStr.isBlank()) throw BusinessError("dateStr is required")

        val pattern = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val zone = ZoneId.systemDefault()
        val start = LocalDateTime.of(LocalDate.parse(dateStr, pattern), LocalTime.MIN).atZone(zone).toInstant()
        val end = LocalDateTime.of(LocalDate.parse(dateStr, pattern), LocalTime.MAX).atZone(zone).toInstant()
        val dateLocalDate = LocalDate.parse(dateStr, pattern)

        //判断是不是今天
        if (LocalDate.now() == dateLocalDate) {
            ctx.json(getAgvStatisticsByStartAndEnd(start, end))
        } else {
            val someDayData = MongoDBManager.collection<AgvStatisticsForDate>().findOne(AgvStatisticsForDate::dateStr eq dateStr)
            val agvStatisticsList: List<AgvStatistics>
            if (someDayData != null) {
                agvStatisticsList = someDayData.agvStatisticsList
            } else {
                agvStatisticsList = getAgvStatisticsByStartAndEnd(start, end)
                saveAgvStatisticsByDate(dateStr, agvStatisticsList)
            }
            ctx.json(agvStatisticsList)
        }
    }

    private fun getAgvStatisticsByStartAndEnd(start: Instant, end: Instant): List<AgvStatistics> {
        val todayTasks = MongoDBManager.collection<JinFengTask>().find(JinFengTask::createdOn gt start, JinFengTask::createdOn lt end).toMutableList()
        val robotMap = todayTasks.groupBy { it.processingRobot }
        val agvStatisticsList = mutableListOf<AgvStatistics>()
        for ((robotName, taskList) in robotMap) {
            val taskTypeMap = taskList.groupingBy { it.taskType }.eachCount()
            if (robotName != null) {
                val sbFullNum = taskTypeMap.filterKeys { it in (taskTypeConstConfig.sbFull) }.values.sum()
                val jcFullNum = taskTypeMap.filterKeys { it in (taskTypeConstConfig.jcFull) }.values.sum()
                val fullNum = sbFullNum + jcFullNum
                val sbEmptyNum = taskTypeMap.filterKeys { it in (taskTypeConstConfig.sbEmpty) }.values.sum()
                val jcEmptyNum = taskTypeMap.filterKeys { it in (taskTypeConstConfig.jcEmpty) }.values.sum()
                val emptyNum = sbEmptyNum + jcEmptyNum
                val agvStatistics = AgvStatistics(robotName, sbFullNum, jcFullNum, fullNum,
                    sbEmptyNum, jcEmptyNum, emptyNum, fullNum + emptyNum)
                agvStatisticsList.add(agvStatistics)
            }
        }
        return agvStatisticsList
    }

    @Synchronized
    private fun saveAgvStatisticsByDate(dateStr: String, agvStatisticsList: List<AgvStatistics>) {
        val someDayData = MongoDBManager.collection<AgvStatisticsForDate>().findOne(AgvStatisticsForDate::dateStr eq dateStr)
        if (someDayData == null) {
            MongoDBManager.collection<AgvStatisticsForDate>().insertOne(AgvStatisticsForDate(dateStr = dateStr, agvStatisticsList = agvStatisticsList))
        }
    }
    fun cancelTask(ctx: Context) {
        val taskId = ctx.pathParam("TaskID")
        val c = MongoDBManager.collection<JinFengTask>()
        val task = c.findOne(JinFengTask::taskID eq taskId)
        if (task == null) {
            ctx.json(mapOf("status" to "1", "errorinfo" to "TaskID无效", "data" to null))
            ctx.status(400)
        } else {
            val robotTask = MongoDBManager.collection<RobotTask>().findOne(RobotTask::outOrderNo eq taskId)
                ?: throw BusinessError("找不到robotTask,outOrderNo:$taskId")

            if (task.state in listOf(ErpBodyState.beforeCreated, ErpBodyState.created)) {
                RobotTaskService.abortTask(robotTask.id, disableVehicle = false)

                ctx.json(mapOf("status" to "0", "errorinfo" to null, "data" to null))
                ctx.status(200)
            } else {
                ctx.json(mapOf("status" to "2", "errorinfo" to "任务已装载货物,开始执行,或者任务已结束", "data" to null))
                ctx.status(400)
            }
        }
    }

    fun unlockSite(task: JinFengTask) {
        try {
            StoreSiteService.unlockSiteIfLocked(task.fromSite, "取消任务解锁库位")
        } catch (e: Exception) {
        }

        try {
            StoreSiteService.unlockSiteIfLocked(task.toSite, "取消任务解锁库位")
        } catch (e: Exception) {
        }

        val b: Boolean = customConfig.specialSiteToWaitSite.containsKey(task.toSite)
        if (b) {
            //解锁锁定的等待点
            try {
                if (task.waitToSite.isNotBlank()) {
                    StoreSiteService.unlockSiteIfLocked(task.waitToSite, "取消任务解锁库位")
                }
            } catch (e: Exception) {
            }
        }
    }

    /**
     * 自己临时用
     */
    fun deleteJinFengTaskSelf(ctx: Context) {
        val c = MongoDBManager.collection<JinFengTask>()
        val a = c.drop()
    }
}

fun registerMockHandlers() {

    val mock = Handlers("")
    mock.post("agv/TaskStatus", MockHandle::mockTellErp, ReqMeta(test = true, auth = false))
    mock.post("testTellErp", MockHandle::testTellErp, ReqMeta(test = true, auth = false))

    val statistics = Handlers("statistics")
    statistics.get("taskList", Handle::taskList, ReqMeta(auth = false, page = true))
    statistics.get("agvList/:dateStr", Handle::agvList, ReqMeta(auth = false, page = true))

    val ext = Handlers("ext")
    ext.post("cancelTask/:TaskID", Handle::cancelTask, ReqMeta(auth = false, page = true))
    ext.delete("deleteJinFengTaskSelf", Handle::deleteJinFengTaskSelf, ReqMeta(auth = false, page = true))


}



data class AgvStatisticsForDate(
    @BsonId var id: String = ObjectId().toHexString(),
    var dateStr: String,//yyyy-MM-dd
    var agvStatisticsList: List<AgvStatistics> = emptyList()
)

class AgvStatistics(
    var agvName: String,
    var sbFullNum: Int,
    var jcFullNum: Int,
    var fullNum: Int,
    var sbEmptyNum: Int,
    var jcEmptyNum: Int,
    var emptyNum: Int,
    var total: Int
)

class TaskTypeConstConfig {
    var taskTypeConst: Map<String, String> = mapOf()

    var sbFull: List<String> = listOf()

    var jcFull: List<String> = listOf()

    var sbEmpty: List<String> = listOf()

    var jcEmpty: List<String> = listOf()
}

//class StatisticsGroupConfig {
//    var sbFull: List<String> = listOf()
//
//    var jcFull: List<String> = listOf()
//
//    var sbEmpty: List<String> = listOf()
//
//    var jcEmpty: List<String> = listOf()
//
//}
/*
object TaskTypeConst {
    const val TYPE_0 = "TYPE_0"
    const val TYPE_1 = "TYPE_1"
    const val TYPE_2 = "TYPE_2"
    const val TYPE_3 = "TYPE_3"
    const val TYPE_4 = "TYPE_4"
    const val TYPE_5 = "TYPE_5"
    const val TYPE_6 = "TYPE_6"
    const val TYPE_7 = "TYPE_7"
    const val TYPE_8 = "TYPE_8"
    const val TYPE_9 = "TYPE_9"
    const val TYPE_10 = "TYPE_10"
    const val TYPE_11 = "TYPE_11"
    const val TYPE_12 = "TYPE_12"
    const val TYPE_13 = "TYPE_13"
    const val TYPE_14 = "TYPE_14"
    const val TYPE_15 = "TYPE_15"
    const val TYPE_16 = "TYPE_16"
    const val TYPE_17 = "TYPE_17"
    const val TYPE_18 = "TYPE_18"
    const val TYPE_19 = "TYPE_19"
    const val TYPE_20 = "TYPE_20"
    const val TYPE_21 = "TYPE_21"

    val sbFull = listOf(TYPE_6, TYPE_7, TYPE_10, TYPE_15, TYPE_17, TYPE_21)

    val jcFull = listOf(TYPE_11)

    val full = sbFull + jcFull

    val sbEmpty = listOf(TYPE_4, TYPE_5, TYPE_8, TYPE_9, TYPE_16, TYPE_18, TYPE_19, TYPE_20)

    val jcEmpty = listOf(TYPE_12, TYPE_13, TYPE_14)

    val empty = sbEmpty + jcEmpty

    val total = full + empty


}*/
