package com.seer.srd.lps2

import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.lps2.LPS2App.mockDetail
import com.seer.srd.lps2.Services.createLoadTask
import com.seer.srd.lps2.Services.findAvailableAgvSites
import com.seer.srd.route.service.VehicleService
import com.seer.srd.vehicle.Vehicle
import io.javalin.http.Context
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.litote.kmongo.set
import org.litote.kmongo.setTo

object Handlers {

  fun listUpSites(ctx: Context) {
    ctx.json(Services.listUpSites())
  }

  fun listDownSites(ctx: Context) {
    ctx.json(Services.listDownSites())
  }

  fun getSiteIdByCurrentPoint(ctx: Context) {
    val siteId = Services.getSiteIdByCurrentPoint()
    ctx.json(mapOf("siteId" to siteId))
  }

//  fun getInfoByTaskId(ctx: Context) {
//    val taskId = ctx.pathParam("taskId")
//    val param = Services.getExtraTaskParamByTaskId(taskId)
//    if (param != null) {
//      var matNum = 0L
//      var stations = ""
//      param.stationToMatMap.forEach {
//        matNum += it.value
//        stations += it.key + ","
//      }
//      ctx.json(
//          mapOf(
//              "stationNum" to param.stationToMatMap.size,
//              "stations" to stations.substring(0, stations.length - 1),
//              "matNum" to matNum.toString()
//          )
//      )
//    } else {
//      ctx.json(mapOf(
//          "stationNum" to "",
//          "stations" to "",
//          "matNum" to ""
//      ))
//    }
//  }

  fun listAgvSites(ctx: Context) {
    ctx.json(Services.listAgvSites())
  }

  fun listSiteToStation(ctx: Context) {
    ctx.json(Services.listSiteToStation())
  }

  fun getSiteByStation(ctx: Context) {
    val station = ctx.pathParam("station")
    val siteId = Services.getSiteByStation(station)
    ctx.json(mapOf("siteId" to siteId))
  }

  fun getSiteToStationById(ctx: Context) {
    val siteId = ctx.pathParam("siteId")
    val downs = Services.listDownSites()
    if (siteId !in downs) throw BusinessError("取料位${siteId}不存在!!")
    ctx.json(Services.getStationsByDownSiteId(siteId))
  }

  fun getTaskInterval(ctx: Context) {
    ctx.json(mapOf("interval" to Services.getTaskInterval()))
  }

  fun handleOpen(ctx: Context) {
    val req = ctx.bodyAsClass(SiteIdType::class.java)
    if (req.siteId !in listOf("B-1", "S-1", "S-2", "S-3", "S-4")) throw BusinessError("舱位不存在${req.siteId}")
    val msg = Services.open(req.siteId)
    if (msg!= "") throw BusinessError(msg)
  }

//  fun handleClose(ctx: Context) {
//    val req = ctx.bodyAsClass(SiteIdType::class.java)
//    if (req.siteId !in listOf("B-1", "S-1", "S-2", "S-3", "S-4")) throw BusinessError("舱位不存在${req.siteId}")
//    Services.close(req.siteId)
//  }

  fun handleLoadTask(ctx: Context) {
    val loadInfoReq = ctx.bodyAsClass(LoadInfoReq::class.java)
    val relation = loadInfoReq.relation.map { WorkStationToMatNum(it.workStation.toUpperCase(), it.matNum) }
    val relationD = relation.distinctBy { it.workStation }
    if (relation.size != relationD.size) throw BusinessError("不能输入相同的工位!!")
    val msg = findAvailableAgvSites(loadInfoReq.copy(relation = relation))
    if (msg != "") throw BusinessError(msg)
    createLoadTask(loadInfoReq.copy(relation = relation))
  }

  fun handleSetTaskInterval(ctx: Context) {
    val req = ctx.bodyAsClass(TaskInterval::class.java)
    if (req.interval < CUSTOM_CONFIG.interval) throw BusinessError("设置的时间间隔不能小于${CUSTOM_CONFIG.interval}!!")
    Services.setTaskInterval(req.interval)
  }

  fun handleResetOpenSiteId(ctx: Context) {
    Services.resetOpenSiteId()
    ctx.status(200)
  }

  fun handleGetOpenSiteId(ctx: Context) {
    ctx.json(mapOf("openSiteId" to Services.getOpenSiteId()))
  }

  fun updateSiteStatus(ctx: Context) {
    val req = ctx.bodyAsClass(AgvSiteInfo::class.java)
    if (req.state > 2 || req.state < 0)  {
      ctx.json(mapOf("success" to false, "info" to "不存在的state: ${req.state}"))
      return
    }
    if (req.siteId !in listOf("B-1", "S-1", "S-2", "S-3", "S-4")){
      ctx.json(mapOf("success" to false, "info" to "不存在的siteId: ${req.siteId}"))
      return
    }
    if (req.state in 1..2){
      if (req.workStation == "") {
        ctx.json(mapOf("success" to false, "info" to "state不为0时，workStation不能为空"))
        return
      }
    }
    if (req.matNum < 0 || req.matNum > 5){
      ctx.json(mapOf("success" to false, "info" to "不正确的matNum: ${req.matNum}"))
      return
    }
    if (req.workStation !in (CUSTOM_CONFIG.workStations + CUSTOM_CONFIG.addStations)){
      if (req.workStation == "" && req.state == 0) {
//        Services.updateAgvSiteInfo(req)
//        ctx.json(mapOf("success" to true, "info" to ""))
//        return
      } else {
        ctx.json(mapOf("success" to false, "info" to "不存在的workStation: ${req.workStation}"))
        return
      }
    }
    if (req.state == 0 && req.workStation != ""){
      ctx.json(mapOf("success" to false, "info" to "state为0时，workStation必须为空"))
      return
    }
    val agvSite = Services.getAgvSiteById(req.siteId)
    if (req.state == agvSite?.state) {
      ctx.json(mapOf("success" to false, "info" to "操作重复！"))
      return
    }
    if (req.curState != agvSite?.state) {
      ctx.json(mapOf("success" to false, "info" to "无效操作！"))
      return
    }
    Services.updateAgvSiteInfo(req)
    ctx.json(mapOf("success" to true, "info" to ""))
  }

  fun checkPwd(ctx: Context) {
    val msg = Services.checkPwd(ctx.bodyAsClass(StationToPwd::class.java))
    if (msg == "true" || msg == "false")  {
      val bool = msg == "true"
      ctx.json(mapOf("pwd" to bool))
    }
    else throw BusinessError(msg)
  }

  fun handleAddRelation(ctx: Context) {
    val req = ctx.bodyAsClass(SiteToStation::class.java)
    if (req.siteId.isBlank()) throw BusinessError("未指定取料位!!")
    val msg = Services.checkSiteToStation(req, "add")
    if (msg != "") throw BusinessError(msg)
    Services.addSiteToStation(req)
  }

  fun handleDeleteRelation(ctx: Context) {
    val req = ctx.bodyAsClass(SiteToStation::class.java)
    if (req.siteId.isBlank()) throw BusinessError("未指定取料位!!")
    val msg = Services.checkSiteToStation(req, "delete")
    if (msg != "") throw BusinessError(msg)
    Services.deleteSiteToStation(req)

  }

  fun handleDeleteAllRelation(ctx: Context) {
    Services.deleteAllSiteToStation(ctx.bodyAsClass(SiteIdType::class.java))
  }

  fun handleSetMockDI(ctx: Context) {
    val req = ctx.bodyAsClass(Mock::class.java)
    mockDetail[0] = req.di0
    mockDetail[1] = req.di1
  }

  fun handleGetMockDI(ctx: Context) {
    ctx.json(mockDetail)
  }

  fun resetUpSiteInfo(ctx: Context) {
    val req = ctx.bodyAsClass(SiteIdType::class.java)
    MongoDBManager.collection<UpSiteToAgvSite>().updateOne(UpSiteToAgvSite::upSiteId eq req.siteId,
        set(UpSiteToAgvSite::agvSiteIds setTo emptyList()))
    Services.upSiteToAgvSiteMap[req.siteId] = emptyList()
  }

  fun resetDownSiteInfo(ctx: Context) {
    val req = ctx.bodyAsClass(SiteIdType::class.java)
    MongoDBManager.collection<DownSiteToAgvSite>().updateOne(DownSiteToAgvSite::downSiteId eq req.siteId,
        set(DownSiteToAgvSite::agvSiteIds setTo emptyList()))
    Services.downSiteToAgvSiteMap[req.siteId] = emptyList()
  }

  fun resetLPS2All(ctx: Context) {
    Services.resetLPS2All()
    ctx.status(200)
  }

  fun updateAGVState(ctx: Context) {
    val vehicle = ctx.pathParam("vehicleName")
    val state =
        when (ctx.queryParam("state")?.toUpperCase()) {
          "UNKNOWN" -> Vehicle.State.UNKNOWN
          "ERROR" -> Vehicle.State.ERROR
          "CHARGING" -> Vehicle.State.CHARGING
          "EXECUTING" -> Vehicle.State.EXECUTING
          "IDLE" -> Vehicle.State.IDLE
          "UNAVAILABLE" -> Vehicle.State.UNAVAILABLE
          else -> Vehicle.State.ERROR
        }
    VehicleService.updateVehicleState(vehicle, state)
  }
}

data class Mock(
    val di0: Boolean,
    val di1: Boolean
)


data class LoadInfoReq(
  val siteId: String,
  val immediate: Boolean = false,
  val relation: List<WorkStationToMatNum>
)

data class WorkStationToMatNum(
  val workStation: String,
  val matNum: Int?
)

data class SiteIdType(
    val siteId: String
)