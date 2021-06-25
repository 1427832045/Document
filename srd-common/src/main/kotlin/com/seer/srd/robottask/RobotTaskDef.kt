package com.seer.srd.robottask

import com.mongodb.client.model.ReplaceOptions
import com.seer.srd.BusinessError
import com.seer.srd.SystemError
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.http.HttpRequestMapping
import com.seer.srd.http.HttpServer.handle
import com.seer.srd.http.ReqMeta
import com.seer.srd.robottask.RobotTaskService.saveNewRobotTask
import com.seer.srd.robottask.component.HasTaskComponents
import com.seer.srd.robottask.component.TaskComponent
import com.seer.srd.robottask.component.processComponents
import com.seer.srd.util.mapper
import io.javalin.http.Context
import io.javalin.http.HandlerType
import org.apache.commons.io.FileUtils
import org.bson.types.ObjectId
import org.litote.kmongo.eq
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

private val LOG = LoggerFactory.getLogger("com.seer.srd.robottask")

//数据类，机器人任务的定义
data class RobotTaskDef(
    var id: ObjectId? = null,
    var name: String = "",
    var description: String = "",
    var httpApiList: MutableList<RobotTaskHttpApiDef> = ArrayList(),
    var transports: MutableList<RobotTransportDef> = ArrayList(),
    var static: Boolean = false,
    var parallel: Boolean = false,
    var noTransport: Boolean = false
)


data class RobotTransportDef(
    var refName: String = "",
    var description: String = "",
    var category: String = "",
    var seqGroup: String = "",
    var stages: MutableList<RobotStageDef> = ArrayList()
) {
    fun stageIndexOfFirstDestination(): Int? {
        for (i in 0 until stages.size) {
            if (stages[i].forRoute == true) return i
        }
        return null
    }
}

class RobotStageDef(
    var refName: String = "",
    var description: String = "",
    // var routeDestIndex: Int? = null,
    var forRoute: Boolean? = false, // 是否是调度的阶段
    var operation: String = "",
    var location: String = "",
    var properties: String = "",
    var maxRetries: Int? = null,
    var retryDelay: Long? = null,
    var components: MutableList<TaskComponent> = ArrayList()
)

class RobotTaskHttpApiDef(
    var path: String = "",
    var method: String = "",
    var reqDemoBody: String = "",
    var successResponseCode: Int = -1,
    var responseDecorator: String? = null
) : HasTaskComponents {
    override var components: List<TaskComponent> = ArrayList()
}


//
private val taskDefs: MutableMap<String, RobotTaskDef> = ConcurrentHashMap()

fun listRobotTaskDefs(): List<RobotTaskDef> {
    return taskDefs.values.sortedBy { it.name }.toList()
}

fun getRobotTaskDef(name: String): RobotTaskDef? {
    return taskDefs[name]
}

fun registerStaticRobotTaskDef(def: RobotTaskDef) {
    taskDefs[def.name] = def
}

@Synchronized
fun loadRobotTaskDefs() {
    val defs = collection<RobotTaskDef>().find().toList()
    taskDefs.clear()
    defs.forEach { def -> taskDefs[def.name] = def }
}

@Synchronized
fun updateRobotTaskDefs(defs: List<RobotTaskDef>) {
    val c = collection<RobotTaskDef>()

    val dupNames = defs.map { it.name }.toMutableList()
    dupNames.distinctBy { it }.forEach { name -> dupNames.remove(name) }
    if (dupNames.isNotEmpty()) throw BusinessError("""导入数据存在相同任务标识: "${dupNames[0]}"""")

    for (def in defs) {
        val paths = c.find().filter { it.name != def.name }.map { taskDef -> taskDef.httpApiList.map { it.path } }.toList()
        def.httpApiList.map { it.path }.forEach { path ->
            paths.forEach { pathList ->
                if (pathList.contains(path)) throw BusinessError("""重复的url: "$path"""")
            }
        }
        taskDefs[def.name] = def
        c.replaceOne(RobotTaskDef::name eq def.name, def, ReplaceOptions().upsert(true))
    }
    loadRobotTaskDefs()
    storeDefsToLocalFile()
}

@Synchronized
fun removeRobotTaskDef(name: String) {
    val def = taskDefs.remove(name) ?: return
    collection<RobotTaskDef>().deleteOne(RobotTaskDef::name eq def.name)
    loadRobotTaskDefs()
    storeDefsToLocalFile()
}

fun registerTaskRoutes() {
    taskDefs.values.forEach { taskDef ->
        for (api in taskDef.httpApiList) {
            val apiHandler = fun(httpCtx: Context) {
                LOG.info("HTTP API of task ${taskDef.name} called")
                if(!httpCtx.body().isBlank()) LOG.debug("get http body: ${httpCtx.body()}")
                // TODO log request body
                val responseDecorator = getHttpResponseDecorator(api.responseDecorator)

                val task = buildTaskInstanceByDef(taskDef)
                val taskCtx = ProcessingContext(task, -1, -1)
                taskCtx.httpCtx = httpCtx
                try {
                    processComponents(api.components, taskCtx)
                    saveNewRobotTask(task)
                    httpCtx.status(if (api.successResponseCode > 0) api.successResponseCode else 201)
                    if (responseDecorator != null) {
                        responseDecorator(task, httpCtx, null)
                    } else {
                        httpCtx.json(mapOf("taskId" to task.id))
                    }
                } catch (err: Exception) {
                    if (responseDecorator != null) {
                        responseDecorator(task, httpCtx, err)
                    } else {
                        throw err
                    }
                }
            }
            val method = if (api.method.toUpperCase() == "PUT") HandlerType.PUT else HandlerType.POST
            val meta = ReqMeta(false, null, true)
            if (api.reqDemoBody.isNotBlank()) meta.reqBodyDemo = listOf(api.reqDemoBody)
            val handler = HttpRequestMapping(method, api.path, apiHandler, meta)
            handle(handler)
        }
    }
}

@Synchronized
private fun storeDefsToLocalFile() {
    val str = mapper.writeValueAsString(taskDefs)
    val backupDir = Paths.get(System.getProperty("user.dir"), "data", "robot-tasks").toFile()
    if (!backupDir.exists()) {
        if (!backupDir.mkdirs()) throw SystemError("Failed to create robot task backup directory")
    }
    val timestamp = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
    val filename = "robot-tasks-$timestamp.ss"
    FileUtils.write(File(backupDir, filename), str, StandardCharsets.UTF_8)
}

fun buildTaskInstanceByDef(taskDef: RobotTaskDef): RobotTask {
    val task = RobotTask(def = taskDef.name)
    task.transports = taskDef.transports.map { transportDef ->
        val rt = RobotTransport(taskId = task.id, category = transportDef.category)
        rt.stages = transportDef.stages.map { RobotStage() }
        rt
    }
    return task
}