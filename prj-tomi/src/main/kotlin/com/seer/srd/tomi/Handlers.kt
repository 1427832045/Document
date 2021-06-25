package com.seer.srd.tomi

import com.seer.srd.BusinessError
import com.seer.srd.Error400
import com.seer.srd.db.MongoDBManager
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.http.Handlers
import com.seer.srd.http.HandlersWithoutApiPrefix
import com.seer.srd.http.ReqMeta
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskService
import com.seer.srd.robottask.buildTaskInstanceByDef
import com.seer.srd.robottask.getRobotTaskDef
import com.seer.srd.tomi.UrlBuilder.tssClient
import com.seer.srd.util.mapper
import io.javalin.http.Context
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val TASK_DEF_TOMI = "TaskDefTomi"

object Handlers {
    private val logger = LoggerFactory.getLogger("com.seer.srd.tomi.Handlers")

    fun registerHandlers() {
        val root = HandlersWithoutApiPrefix("")
        root.post("abort-order/:ids", ::abortTaskByOutOrderIds, ReqMeta(test = true, auth = false))
        root.post("submit-order", ::handleCreateTask, ReqMeta(test = true, auth = false, reqBodyDemo = listOf("""["LM123","AP123"]""")))
    }

    private fun abortTaskByOutOrderIds(ctx: Context) {
        val ids = ctx.pathParam("ids").split(",")
        try {
            for (id in ids) {
                val task = collection<RobotTask>().findOne(RobotTask::outOrderNo eq id)
                    ?: throw BusinessError("找不到id: $id")
                RobotTaskService.abortTask(task.id, disableVehicle = false)
            }
            ctx.json(mapOf("statusCode" to "S001", "message" to "success"))
        } catch (e: Exception) {
            ctx.json(mapOf("statusCode" to "S002", "message" to "fail"))
        }
    }

    private fun handleCreateTask(ctx: Context) {
        val bodyStr = ctx.body()
        val body = mapper.readTree(bodyStr)
        val locations = mutableListOf<String>()
        body.forEach { locations.add(it.asText().trim()) }
        val locationsSize = locations.size
        if (locationsSize == 0) throw Error400("EmptyLocationList", "")

        logger.info("size=${body.size()}, $body, $locations")
        val taskDef = getRobotTaskDef(TASK_DEF_TOMI)
            ?: throw Error400("CannotFindTaskDef", TASK_DEF_TOMI)

        val transSize = taskDef.transports.size
        if (locationsSize > transSize)
            throw Error400("TooManyLocations", "站点数量【$locationsSize】超过了最大运单数量【$transSize】")

        val newTask = buildTaskInstanceByDef(taskDef)
        val outOrderNo = buildOutOrderNo()
        newTask.outOrderNo = outOrderNo

        locations.forEachIndexed { index, location ->
            newTask.transports[index].stages.forEach { it.location = location }
        }

        RobotTaskService.saveNewRobotTask(newTask)

        ctx.json(mapOf("taskId" to outOrderNo))
    }

    private fun buildOutOrderNo(): String {
        // yyyyMMddxxxxxx
        val dateStr = LocalDate.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val firstNo = "${dateStr}000001"
        val lastOne = MongoDBManager.collection<RobotTask>().find()
            .filter { !it.outOrderNo.isNullOrBlank() }
            .maxBy { it.createdOn }
            ?: return firstNo
        val lastOutOrderNo = lastOne.outOrderNo!!
        val lastDate = lastOutOrderNo.substring(0, 8)
        return if (lastDate == dateStr) lastOutOrderNo.toLong().plus(1).toString() else firstNo
    }
}

object EventHandlers {
    fun onRobotTaskDispatched(task: RobotTask) {
        if (task.persistedVariables["feedback"] == true) return
        val vehicleId = task.transports.firstOrNull()?.processingRobot ?: return
        val taskId = task.outOrderNo ?: return
        task.persistedVariables["feedback"] = true
        tssClient.feedback(taskId, vehicleId).execute()
    }
}