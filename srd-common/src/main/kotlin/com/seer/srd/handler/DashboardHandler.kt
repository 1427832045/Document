package com.seer.srd.handler

import com.seer.srd.Error400
import com.seer.srd.SystemError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.util.mapper
import io.javalin.http.Context
import org.apache.commons.io.FileUtils
import org.litote.kmongo.ascendingSort
import org.litote.kmongo.find
import org.litote.kmongo.gte
import org.litote.kmongo.lt
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.time.*
import java.time.format.DateTimeFormatter

private val logger = LoggerFactory.getLogger("com.seer.srd.handler.dashboard")

private val dashboardSample = """[{"id":"Test1","title":{"title":"SRD可视化界面"},"area":{"split":"v","splitSize":1182,
    |"areas":[{"split":"v","splitSize":961,"areas":[{"split":"c","splitSize":0,"areas":[],"content":"chart",
    |"contentConfig":{"types":["RobotTaskCreated","RobotTaskFinished","RobotTaskSuccess","RobotTaskFailed"],
    |"legend":"111","chart":"bar","timeUnit":"second","level":"Hour","start":"2021-04-09 00","end":"2021-04-09 23"}},
    |{"split":"c","splitSize":0,"areas":[],"content":"chart","contentConfig":{"chart":"bar","type":"RobotTaskFinshed",
    |"level":"Day","start":"2021-04-01","end":"2021-04-09","types":["VehicleExecutingTime","VehicleIdleTime",
    |"VehicleChargingNum","VehicleChargingTime"],"legend":"2222","timeUnit":"second"}}],"content":"none",
    |"contentConfig":""}],"content":"none","contentConfig":""}}]""".trimMargin()

fun handleGetDashboardConfigs(ctx: Context) {
    val dashboardDir = Paths.get(System.getProperty("user.dir"), "data", "dashboards").toFile()
    if (!dashboardDir.exists() && !dashboardDir.mkdirs())
        throw Error400("ConfigFileMissed", "Cannot create dashboard dir!")

    val configFile = File(dashboardDir, "dashboard.json")
    var configString = dashboardSample
    if (!configFile.exists()) {
        // 没有配置文件时，创建默认的配置文件
        FileUtils.write(File(dashboardDir, "dashboard.json"), configString, StandardCharsets.UTF_8)
        logger.info("init dashboard config file...")
        // throw Error400("ConfigFileMissed", "Cannot find dashboard config file!")
    }
    configString = FileUtils.readFileToString(configFile, StandardCharsets.UTF_8)
    ctx.json(mapper.readTree(configString))
}

@Synchronized
fun handleSetDashboardConfigs(ctx: Context) {
    logger.debug("update dashboard config.")
    val configString = ctx.body()
    val dashboardDir = Paths.get(System.getProperty("user.dir"), "data", "dashboards").toFile()
    if (!dashboardDir.exists()) {
        if (!dashboardDir.mkdirs()) throw SystemError("Failed to create dashboard backup directory")
    }
    // 重命名已存在的文件
    val configFile = File(dashboardDir, "dashboard.json")
    if (configFile.exists()) {
        val timestamp = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
        val filename = "dashboard-$timestamp.json"
        configFile.renameTo(File(dashboardDir, filename))
    }
    FileUtils.write(File(dashboardDir, "dashboard.json"), configString, StandardCharsets.UTF_8)
    ctx.status(204)
}

fun handleGetRobotTaskDashboardData(ctx: Context) {
    val df = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val nowStr = Instant.now().toString().substring(0, 10)
    val start = LocalDateTime.of(LocalDate.parse(nowStr, df), LocalTime.MIN).atZone(ZoneId.systemDefault()).toInstant()
    val end = LocalDateTime.of(LocalDate.parse(nowStr, df), LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant()
    val summary = Summary()
    val stageStates: StageStates = mutableListOf()
    var stageNum = 0
    val tasks = MongoDBManager.collection<RobotTask>()
        .find(RobotTask::createdOn gte start, RobotTask::createdOn lt end)
        .ascendingSort(RobotTask::createdOn)
        .toList()
    tasks.forEach { task ->
        when (task.state) {
            RobotTaskState.Success -> summary.success++
            RobotTaskState.Failed -> summary.fail++
            RobotTaskState.Aborted -> summary.cancel++
            RobotTaskState.Created -> {
                var processing = false
                val taskStageStates: MutableList<Int> = mutableListOf()
                stageStates.add(taskStageStates)
                task.transports.forEach { transport ->
                    if (transport.processingRobot != null) processing = true
                    transport.stages.forEach { stage ->
                        taskStageStates.add(stage.state)
                        stageNum++
                    }
                }
                if (processing) summary.going++
                else summary.init++
            }
        }
    }

    ctx.json(DashboardResponse(summary, stageStates, tasks.size, stageNum))
    ctx.status(201)
}

typealias StageStates = MutableList<MutableList<Int>>

data class DashboardResponse(
    val summary: Summary,
    val stages: StageStates,
    val total: Int,
    val stageNum: Int
)

data class Summary(
    var success: Int = 0,       // 成功的任务数量
    var fail: Int = 0,          // 失败测任务数量
    var cancel: Int = 0,        // 被撤销的任务数量
    var going: Int = 0,         // 已经下发给调度的任务数量
    var init: Int = 0           // 还未执行的任务数量
)