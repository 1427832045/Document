package com.seer.srd.hongjiang

import com.seer.srd.Application
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.eventbus.EventBus
import com.seer.srd.http.HttpRequestMapping
import com.seer.srd.http.HttpServer.handle
import com.seer.srd.http.ReqMeta
import com.seer.srd.robottask.*
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.setVersion
import io.javalin.http.Context
import io.javalin.http.HandlerType
import org.litote.kmongo.and
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.litote.kmongo.lt
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

fun main() {
    setVersion("HongJiang", "2.0.1")

    Application.initialize()

    HongJiangApp.init()

    EventBus.robotTaskFinishedEventBus.add(HongJiangApp::onTaskFinished)

    Application.start()
}

object HongJiangApp {

//    val cutterMap: MutableMap<String, CutterReq> = ConcurrentHashMap()

    // 保存未完成的返回任务
    val cutterBackMap: MutableMap<String, CutterBackReq> = ConcurrentHashMap()

    private val logger = LoggerFactory.getLogger(HongJiangApp::class.java)

    private val extraDecorator: HttpResponseDecorator =  { task, ctx, err ->
        if (err != null) throw err
        val msg = task.persistedVariables["msg"] as String?
        if (!msg.isNullOrBlank()) ctx.json(mapOf("taskId" to task.id, "msg" to msg))
        else ctx.json(mapOf("taskId" to task.id))
    }

    fun init() {

        registerRobotTaskComponents(ExtraComponent.extraComponents)

        registerHttpResponseDecorator("msg", extraDecorator)

//        val unfinishedTasks = MongoDBManager.collection<RobotTask>()
//            .find(and(RobotTask::def eq "cutter", RobotTask::state lt RobotTaskState.Success))

        val unfinishedTasks = MongoDBManager.collection<RobotTask>()
            .find(and(RobotTask::def eq "cutterBack", RobotTask::state lt RobotTaskState.Success)).toList()

        unfinishedTasks.forEach {
            val from = it.persistedVariables["from"] as String? ?: ""
            val to = it.persistedVariables["to"] as String? ?: ""
            val preTaskId = it.persistedVariables["preTaskId"] as String? ?: ""
            val preTask =  MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq preTaskId)
            val canSend = preTask != null && preTask.state > RobotTaskState.Success

            cutterBackMap[it.id] = CutterBackReq(from, to, canSend)
        }

        handle(
            HttpRequestMapping(HandlerType.GET, "ext/user-config", ::listConfig, ReqMeta(auth = false, test = false)),
            HttpRequestMapping(HandlerType.POST, "ext/check-password", ::check, ReqMeta(auth = false, test = false)),
            HttpRequestMapping(HandlerType.POST, "ext/withdraw", ::withdrawTask, ReqMeta(auth = false, test = false))
        )
    }

    fun onTaskFinished(task: RobotTask) {
        if (task.def == "cutterBack" && cutterBackMap.containsKey(task.id)) {
            val req = cutterBackMap.remove(task.id)
            if (req != null) logger.debug("${task.id} is terminated, from=${req.from}, to=${req.to}")
            else logger.debug("${task.id} is terminated without fromSite and toSite")   // 一般不会出现
        }
    }

    private fun listConfig(ctx: Context) {
      ctx.json(CUSTOM_CONFIG.userConfig)
    }

    private fun withdrawTask(ctx: Context) {
      val req = ctx.bodyAsClass(WithdrawReq::class.java)
      val task = req.task
      val workStation = req.workStation
      if (!CUSTOM_CONFIG.canWithdrawAll) {
        if (workStation.isBlank()) throw BusinessError("未设置工位")
        if (!workStation.contains("admin")) {
          if (task.workStations.isEmpty() || !task.workStations.contains(workStation)) throw BusinessError("权限不足")
        }
      }
      if (task.state >= RobotTaskState.Success) throw BusinessError("任务已经结束")
      if (task.transports[0].processingRobot != null) throw BusinessError("任务正在执行")
      val preTaskId = task.persistedVariables["preTaskId"] as String?
      if (!preTaskId.isNullOrBlank()) {
        val preTask = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq preTaskId)
        if (preTask != null && preTask.state < RobotTaskState.Success) throw BusinessError("前序任务${preTaskId}未完成")
      }
      RobotTaskService.abortTask(task.id)
    }

    private fun check(ctx: Context) {
      val newWt = ctx.queryParam("newWt") ?: throw BusinessError("岗位不能为空")
      val newWs = ctx.queryParam("newWs") ?: throw BusinessError("工位不能为空")
      val oldWt = ctx.queryParam("oldWt")
      val oldWs = ctx.queryParam("oldWs")
      val pwd = ctx.queryParam("pwd")
      if (pwd.isNullOrBlank()) throw BusinessError("密码不能为空")
      logger.debug("old work type=$oldWt, old work station=$oldWs, new work type=$newWt, new work station=$newWs")
      val findWs = CUSTOM_CONFIG.userConfig.filter { it.workStation == newWs }
      if (findWs.isEmpty()) {
        if (pwd.trim() != newWs.trim()) throw BusinessError("密码输入错误")
      } else {
        if(findWs.size > 1) throw BusinessError("工位信息配置异常，请检查服务器配置")
        if (pwd.trim() != findWs[0].pwd.trim()) throw BusinessError("密码输入错误")
      }
    }
}

data class CutterBackReq(
    val from: String = "",
    val to: String = "",
    val canSend: Boolean = false
)

data class WithdrawReq(
    val task: RobotTask,
    var workStation: String
)