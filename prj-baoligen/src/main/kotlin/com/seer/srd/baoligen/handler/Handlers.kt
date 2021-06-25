package com.seer.srd.baoligen.handler

import com.seer.srd.BusinessError
import com.seer.srd.baoligen.CustomHttpClient.takeMatFinished
import com.seer.srd.baoligen.CustomHttpClient.taskFinished
import com.seer.srd.baoligen.CustomResponse
import com.seer.srd.baoligen.TakeMatFinished
import com.seer.srd.baoligen.TaskFinished
import com.seer.srd.baoligen.checkStoreSiteAvailable
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskService
import com.seer.srd.robottask.RobotTaskService.saveNewRobotTask
import com.seer.srd.robottask.buildTaskInstanceByDef
import com.seer.srd.robottask.getRobotTaskDef
import com.seer.srd.util.mapper
import io.javalin.http.Context
import io.javalin.plugin.json.JavalinJson
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.slf4j.LoggerFactory

const val TRANSPORT_MAT = "TaskDefTransportMat"
const val RETURN_TRAY = "TaskDefReturnTray"

private val logger = LoggerFactory.getLogger("com.seer.srd.baoligen.handler")

fun newTask(ctx: Context) {
    try {
        val taskInfo = ctx.bodyAsClass(TaskInfo::class.java)
        val taskType = taskInfo.taskType ?: throw BusinessError("缺少任务类型！")
        val fromSiteId = taskInfo.fromSite ?: throw BusinessError("缺少起点库位！")
        checkStoreSiteAvailable(fromSiteId)
        val toSiteId = taskInfo.toSite ?: throw BusinessError("缺少终点库位！")
        checkStoreSiteAvailable(toSiteId)

        val taskDef = when (taskType) {
            "transportMat" -> getRobotTaskDef(TRANSPORT_MAT)
                ?: throw BusinessError("无法找到任务定义$TRANSPORT_MAT，请联系开发人员！")
            "returnTray" -> getRobotTaskDef(RETURN_TRAY)
                ?: throw BusinessError("无法找到任务定义$RETURN_TRAY，请联系开发人员！")
            else -> throw BusinessError("无法识别任务类型$taskType，请输入正确的任务类型！")
        }

        val newTask = buildTaskInstanceByDef(taskDef)
        newTask.persistedVariables["fromSiteId"] = fromSiteId
        newTask.persistedVariables["toSiteId"] = toSiteId

        saveNewRobotTask(newTask)

        ctx.json(mapper.readTree("""{"taskId": "${newTask.id}"}"""))
        ctx.status(201)

    } catch (e: Exception) {
        val resObj = CustomResponse(false, e.message ?: "未知原因，请联系开发人员！")
        ctx.json(mapper.readTree(JavalinJson.toJson(resObj)))
        ctx.status(400)
    }
}

@Synchronized
fun processingRobot(ctx: Context) {
    val taskId = ctx.pathParam("taskId")

    val robot = getProcessingRobot(taskId)

    ctx.json(mapper.readTree("""{"taskId":"$taskId", "processingRobot": "$robot"}"""))
    ctx.status(201)
}

fun getProcessingRobot(taskId: String): String {
    val task = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq taskId)
        ?: throw BusinessError("No task=${taskId}!")

    for (pr in task.transports.mapNotNull { it.processingRobot }) {
        if (pr.isNotBlank()) return pr
    }

    // 此任务未被AGV执行
    return "-"
}

@Synchronized
fun customAbortTask(ctx: Context) {
    var taskId = "-"
    try {
        taskId = ctx.pathParam("taskId")

        MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq taskId)
            ?: throw BusinessError("No task=$taskId")

        logger.debug("abort task=$taskId from client=${ctx.req.remoteAddr}.")
        RobotTaskService.abortTask(taskId)

        ctx.status(204)
    } catch (e: Exception) {
        ctx.json(mapper.readTree("""{"taskId":"$taskId", "message": "$e"}"""))
        ctx.status(400)
    }
}

fun customTakeMatFinished(ctx: Context) {
    val reqBody = ctx.bodyAsClass(TakeMatFinished::class.java)
    logger.debug(reqBody.toString())

    ctx.json(takeMatFinished(reqBody) ?: "request failed.")
}

fun customTaskFinished(ctx: Context) {
    val reqBody = ctx.bodyAsClass(TaskFinished::class.java)
    logger.debug(reqBody.toString())
    ctx.json(taskFinished(reqBody) ?: "request failed.")
}

fun customTaskMatFinishedMock(ctx: Context) {
    val resBody = ctx.bodyAsClass(TakeMatFinished::class.java)
    logger.debug("take-mat-finished-mock ${resBody}.")
    ctx.json(mapper.readTree("""{"success": true, "message": "OK"}"""))
    ctx.status(401)
}

fun customTaskFinishedMock(ctx: Context) {
    val resBody = ctx.bodyAsClass(TaskFinished::class.java)
    logger.debug("task-finished-mock ${resBody}.")
    ctx.json(mapper.readTree("""{"success": true, "message": "OK"}"""))
    ctx.status(401)
}

data class TaskInfo(
    val taskType: String? = null,
    val fromSite: String? = null,
    val toSite: String? = null
)