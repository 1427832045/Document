package com.seer.srd.lps2

import com.mongodb.client.model.Accumulators
import com.mongodb.client.model.Filters
import com.seer.srd.Application
import com.seer.srd.I18N
import com.seer.srd.db.MongoDBManager
import com.seer.srd.eventbus.EventBus
import com.seer.srd.http.HttpRequestMapping
import com.seer.srd.http.HttpServer.handle
import com.seer.srd.http.ReqMeta
import com.seer.srd.lps2.ExtraComponents.extraComponents
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.setVersion
import com.seer.srd.stats.StatAccount
import com.seer.srd.stats.statAccounts
import io.javalin.http.HandlerType
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.util.*

fun main() {
  LPS2App.init()
}

object LPS2App {

  private val logger = LoggerFactory.getLogger(ExtraComponents::class.java)

  @Volatile
  var mockDetail = mutableListOf(false, false)

  fun init() {
    setVersion("LPS2", "3.0.19.1")
    I18N.loadDict("/mat.csv")

    statAccounts.addAll(Collections.synchronizedList(listOf(
        StatAccount(
            "StationSum", "MatRecord", "recordOn", listOf(Accumulators.sum("value", 1)),
            Filters.ne("station", "")
        ),
        StatAccount(
            "MatSum", "MatRecord", "recordOn", listOf(Accumulators.sum("value", "\$matNum"))
        )
    )))

    registerRobotTaskComponents(extraComponents)

    handle(HttpRequestMapping(
        HandlerType.GET, "listUpSites", Handlers::listUpSites, ReqMeta(auth = false, test = true))
    )

    handle(HttpRequestMapping(
        HandlerType.GET, "listDownSites", Handlers::listDownSites, ReqMeta(auth = false, test = true))
    )

    handle(HttpRequestMapping(
        HandlerType.GET, "listAgvSites", Handlers::listAgvSites, ReqMeta(auth = false, test = true))
    )

    handle(HttpRequestMapping(
        HandlerType.GET, "getSiteByStation/:station", Handlers::getSiteByStation, ReqMeta(auth = false, test = true))
    )

    handle(HttpRequestMapping(
        HandlerType.GET, "listSiteToStation", Handlers::listSiteToStation, ReqMeta(auth = false, test = true))
    )

    handle(HttpRequestMapping(
        HandlerType.GET, "getStationsById/:siteId", Handlers::getSiteToStationById, ReqMeta(auth = false, test = true))
    )

    handle(HttpRequestMapping(
        HandlerType.GET, "getTaskInterval", Handlers::getTaskInterval, ReqMeta(auth = false, test = true))
    )

    handle(HttpRequestMapping(
        HandlerType.GET, "getSiteIdByCurrentPoint", Handlers::getSiteIdByCurrentPoint, ReqMeta(auth = false, test = true))
    )

//    handle(HttpRequestMapping(
//        HandlerType.GET, "getInfoByTaskId/:taskId", Handlers::getInfoByTaskId, ReqMeta(auth = false, test = true))
//    )

    handle(HttpRequestMapping(
        HandlerType.POST, "open", Handlers::handleOpen, ReqMeta(auth = false, test = true))
    )

//    handle(HttpRequestMapping(
//        HandlerType.POST, "close", Handlers::handleClose, ReqMeta(auth = false, test = true))
//    )

    handle(HttpRequestMapping(
        HandlerType.POST, "addRelation", Handlers::handleAddRelation,
        ReqMeta(auth = false, test = true, reqBodyDemo = listOf("""
          {"siteId":"DOWN-01",
          "workStations": ["X01"]
          }
        """.trimIndent())))
    )

    handle(HttpRequestMapping(
        HandlerType.POST, "deleteRelation", Handlers::handleDeleteRelation,
        ReqMeta(auth = false, test = true, reqBodyDemo = listOf("""
          {"siteId":"DOWN-01",
          "workStations": ["X01"]
          }
        """.trimIndent())))
    )

    handle(HttpRequestMapping(
        HandlerType.POST, "deleteAllRelations", Handlers::handleDeleteAllRelation,
        ReqMeta(auth = false, test = true, reqBodyDemo = listOf("""{"siteId":"DOWN-01"}""".trimIndent())))
    )

    handle(HttpRequestMapping(
        HandlerType.POST, "load", Handlers::handleLoadTask,
        ReqMeta(auth = false, test = true, reqBodyDemo = listOf("""
          {"siteId":"Planner-03",
          "immediate": true,
          "relation": [{"workStation": "X01", "matNum": 3}]
          }
        """.trimIndent())))
    )

    handle(HttpRequestMapping(
        HandlerType.POST, "setTaskInterval", Handlers::handleSetTaskInterval,
        ReqMeta(auth = false, test = true, reqBodyDemo = listOf("""{"interval": 5}""".trimIndent())))
    )

    handle(HttpRequestMapping(
        HandlerType.POST, "resetOpenSiteId", Handlers::handleResetOpenSiteId,
        ReqMeta(auth = false, test = true))
    )

    handle(HttpRequestMapping(
        HandlerType.GET, "getOpenSiteId", Handlers::handleGetOpenSiteId,
        ReqMeta(auth = false, test = true))
    )

    handle(HttpRequestMapping(
        HandlerType.POST, "updateAgvSite", Handlers::updateSiteStatus,
        ReqMeta(auth = false, test = true, reqBodyDemo = listOf("""
          {"siteId":"B-1",
          "state": 0,
          "curState": 0,
          "workStation": "",
          "matNum": 0
          }
        """.trimIndent())))
    )

    handle(HttpRequestMapping(HandlerType.POST, "checkPwd", Handlers::checkPwd, ReqMeta(auth = false, test = true,
        reqBodyDemo = listOf("""{"workStation":"X02","pwd": "X02"}""".trimIndent()))))


    handle(HttpRequestMapping(
        HandlerType.POST, "setDI", Handlers::handleSetMockDI,
        ReqMeta(auth = false, test = true,
            reqBodyDemo = listOf("""{"di0": true,"di1": true}""".trimIndent())))
    )

    handle(HttpRequestMapping(
        HandlerType.GET, "getDI", Handlers::handleGetMockDI,
        ReqMeta(auth = false, test = true))
    )

    handle(HttpRequestMapping(
        HandlerType.POST, "resetUpSiteInfo", Handlers::resetUpSiteInfo,
        ReqMeta(auth = false, test = true,
            reqBodyDemo = listOf("""{"siteId": "Planner-01"}""".trimIndent())))
    )

    handle(HttpRequestMapping(
        HandlerType.POST, "resetDownSiteInfo", Handlers::resetDownSiteInfo,
        ReqMeta(auth = false, test = true,
            reqBodyDemo = listOf("""{"siteId": "DOWN-01"}""".trimIndent())))
    )

    handle(HttpRequestMapping(
        HandlerType.POST, "resetLPS2All", Handlers::resetLPS2All,
        ReqMeta(auth = false, test = true))
    )

    handle(HttpRequestMapping(
        HandlerType.POST, "updateAGVState/:vehicleName", Handlers::updateAGVState,
        ReqMeta(auth = false))
    )

    handle(HttpRequestMapping(
        HandlerType.POST, "downloadTask", Handlers::updateAGVState,
        ReqMeta(auth = false))
    )

    Application.initialize()

    DownTask.init()

    Services.initAgvSites()

    EventBus.robotTaskFinishedEventBus.add(::onRobotTaskFinished)

    Application.start()


    Runtime.getRuntime().addShutdownHook(Thread {
      DownTask.dispose()
    })

  }

  private fun onRobotTaskFinished(task: RobotTask) {
    logger.debug("完成任务后的检查")
    if (task.def == CUSTOM_CONFIG.load) {
      val upSiteInfos = MongoDBManager.collection<UpSiteToAgvSite>().find().toMutableList()
      upSiteInfos.forEach {
        if (!it.agvSiteIds.isNullOrEmpty()) {
          // 检查是否有送料任务
          val existed = MongoDBManager.collection<RobotTask>().findOne(org.litote.kmongo.and(
              RobotTask::state lt RobotTaskState.Success,
              RobotTask::def eq CUSTOM_CONFIG.load
          ))
          if (existed == null) {
            logger.error("工作站异常, ${it.upSiteId}存在未正常完成的任务")
            logger.error("异常舱位 ${it.agvSiteIds}")
          }
        }
      }
    }
    if (task.def == CUSTOM_CONFIG.take) {
      val downSiteInfos = MongoDBManager.collection<DownSiteToAgvSite>().find().toMutableList()
      downSiteInfos.forEach {
        if (!it.agvSiteIds.isNullOrEmpty()) {
          val existed = MongoDBManager.collection<RobotTask>().findOne(org.litote.kmongo.and(
              RobotTask::state lt RobotTaskState.Success,
              RobotTask::def eq CUSTOM_CONFIG.take
          ))
          if (existed == null) {
            logger.error("工作站异常, ${it.downSiteId}存在未正常完成的任务")
            logger.error("异常舱位 ${it.agvSiteIds}")
          }
        }
      }
    }
  }

}
