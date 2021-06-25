package com.seer.srd.lps2

import com.mongodb.client.model.UpdateOptions
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.*
import com.seer.srd.route.service.PlantModelService
import com.seer.srd.route.service.VehicleService
import com.seer.srd.storesite.StoreSite
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object Services {

//  @Volatile
//  var openSiteId: String? = null

  private val logger = LoggerFactory.getLogger(Services::class.java)

  val upSiteToAgvSiteMap: MutableMap<String, List<String>> = ConcurrentHashMap()

  val downSiteToAgvSiteMap: MutableMap<String, List<String>> = ConcurrentHashMap()

  fun initAgvSites() {
    for (siteId in listOf("B-1", "S-1", "S-2", "S-3", "S-4")) {
      val site = MongoDBManager.collection<AgvSiteInfo>().findOne(
          AgvSiteInfo::siteId eq siteId
      )
      if (site == null)
        MongoDBManager.collection<AgvSiteInfo>().insertOne(
            AgvSiteInfo(siteId, 0, -1, "", 0))
    }
    val downSites = listDownSites()
    for (down in downSites) {
      val siteToStation = MongoDBManager.collection<SiteToStation>().findOne(
          SiteToStation::siteId eq down
      )
      if (siteToStation == null)
        MongoDBManager.collection<SiteToStation>().insertOne(SiteToStation(down, emptyList()))
    }
    val ups = MongoDBManager.collection<UpSiteToAgvSite>().find().toMutableList()
    ups.forEach {
      upSiteToAgvSiteMap[it.upSiteId] = it.agvSiteIds
    }
    val downs = MongoDBManager.collection<DownSiteToAgvSite>().find().toMutableList()
    downs.forEach {
      downSiteToAgvSiteMap[it.downSiteId] = it.agvSiteIds
    }
  }

  @Synchronized
  fun createLoadTask2(loadInfoReq: LoadInfoReq) {
    val toSite = loadInfoReq.siteId
    val immediate = loadInfoReq.immediate
    val relation = loadInfoReq.relation

    val agvSites = listAgvSites().filter { it.state == 0 && it.matNum == 0 && it.workStation == "" }
    if (agvSites.size == 5){
      DownTask.futureTask.cancel(true)
      DownTask.futureTask =
          DownTask.downTaskTimer.scheduleAtFixedRate(
              DownTask::createDownTask,
              Services.getTaskInterval().toLong(),
              Services.getTaskInterval().toLong(),
              TimeUnit.MINUTES
          )
    }

    for (r in relation) {
      if (r.matNum!! > 2) {
        MongoDBManager.collection<AgvSiteInfo>().updateOne(AgvSiteInfo::siteId eq "B-1",
            set(AgvSiteInfo::state setTo 1,
                AgvSiteInfo::matNum setTo  r.matNum,
                AgvSiteInfo::workStation setTo r.workStation),
            UpdateOptions().upsert(true))
        // 更新UpSiteToAgvSite
        MongoDBManager.collection<UpSiteToAgvSite>().updateOne(UpSiteToAgvSite::upSiteId eq toSite,
            addToSet(UpSiteToAgvSite::agvSiteIds, "B-1"), UpdateOptions().upsert(true))
      } else {
        for (siteId in listOf("S-1", "S-2", "S-3", "S-4", "B-1")) {
          val site = MongoDBManager.collection<AgvSiteInfo>().findOne(AgvSiteInfo::siteId eq siteId)
          if (site != null && site.matNum > 0) continue
          // 更新AgvSiteInfo
          MongoDBManager.collection<AgvSiteInfo>().updateOne(AgvSiteInfo::siteId eq siteId,
              set(AgvSiteInfo::state setTo 1,
                  AgvSiteInfo::matNum setTo  r.matNum,
                  AgvSiteInfo::workStation setTo r.workStation),
              UpdateOptions().upsert(true))
          // 更新UpSiteToAgvSite
          MongoDBManager.collection<UpSiteToAgvSite>().updateOne(UpSiteToAgvSite::upSiteId eq toSite,
              addToSet(UpSiteToAgvSite::agvSiteIds, siteId), UpdateOptions().upsert(true))
          break
        }
      }
    }
    val def = getRobotTaskDef("load4") ?: throw BusinessError("NoSuchTaskDef: 'load4'")
    val task = buildTaskInstanceByDef(def)
    task.persistedVariables["immediate"] = immediate
    task.persistedVariables["B-1"] = ""
    task.persistedVariables["S-1"] = ""
    task.persistedVariables["S-2"] = ""
    task.persistedVariables["S-3"] = ""
    task.persistedVariables["S-4"] = ""
    task.persistedVariables["toSite"] = toSite
//    task.transports[1].stages[0].location = toSite
//    task.transports[1].stages[1].location = toSite
//    task.transports[1].stages[2].location = toSite
    RobotTaskService.saveNewRobotTask(task)


  }

  @Synchronized
  fun createLoadTask(loadInfoReq: LoadInfoReq) {
    val toSite = loadInfoReq.siteId
    val immediate = loadInfoReq.immediate
    val relation = loadInfoReq.relation
    val stations = mutableListOf<String>()

    val agvSites = listAgvSites().filter { it.state == 0 && it.matNum == 0 && it.workStation == "" }
    if (agvSites.size == 5){
      DownTask.futureTask.cancel(true)
      DownTask.futureTask =
          DownTask.downTaskTimer.scheduleAtFixedRate(
              DownTask::createDownTask,
              Services.getTaskInterval().toLong(),
              Services.getTaskInterval().toLong(),
              TimeUnit.MINUTES
          )
    }

    val def = getRobotTaskDef(CUSTOM_CONFIG.load) ?: throw BusinessError("NoSuchTaskDef: '${CUSTOM_CONFIG.load}'")
    val task = buildTaskInstanceByDef(def)

    val toWorkStations = mutableListOf<AgvSiteInfo>()
    for (r in relation) {
      if (r.matNum!! > 2) {
        MongoDBManager.collection<AgvSiteInfo>().updateOne(AgvSiteInfo::siteId eq "B-1",
            set(AgvSiteInfo::state setTo 1,
                AgvSiteInfo::matNum setTo  r.matNum,
                AgvSiteInfo::workStation setTo r.workStation),
            UpdateOptions().upsert(true))
        // 设置属性
        stations.add("B-1")
        task.persistedVariables["B-1"] = r.workStation
        toWorkStations.add(AgvSiteInfo("B-1", matNum = r.matNum, workStation = r.workStation))
        // 更新UpSiteToAgvSite
        if (upSiteToAgvSiteMap[toSite].isNullOrEmpty()) upSiteToAgvSiteMap[toSite] = listOf("B-1")
        else upSiteToAgvSiteMap[toSite] = upSiteToAgvSiteMap[toSite]!!.toMutableList().let {
          it.add("B-1")
          it
        }
        MongoDBManager.collection<UpSiteToAgvSite>().updateOne(UpSiteToAgvSite::upSiteId eq toSite,
            addToSet(UpSiteToAgvSite::agvSiteIds, "B-1"), UpdateOptions().upsert(true))
      } else {
        for (siteId in listOf("S-1", "S-2", "S-3", "S-4", "B-1")) {
          val site = MongoDBManager.collection<AgvSiteInfo>().findOne(AgvSiteInfo::siteId eq siteId)
          if (site != null && site.matNum > 0) continue
          // 更新AgvSiteInfo
          MongoDBManager.collection<AgvSiteInfo>().updateOne(AgvSiteInfo::siteId eq siteId,
              set(AgvSiteInfo::state setTo 1,
                  AgvSiteInfo::matNum setTo  r.matNum,
                  AgvSiteInfo::workStation setTo r.workStation),
              UpdateOptions().upsert(true))
          // 设置工作站
          stations.add(siteId)
          task.persistedVariables[siteId] = r.workStation
          toWorkStations.add(AgvSiteInfo(siteId, matNum = r.matNum, workStation = r.workStation))
          // 更新UpSiteToAgvSite
          if (upSiteToAgvSiteMap[toSite].isNullOrEmpty()) upSiteToAgvSiteMap[toSite] = listOf(siteId)
          else upSiteToAgvSiteMap[toSite] = upSiteToAgvSiteMap[toSite]!!.toMutableList().let {
            it.add(siteId)
            it
          }
          MongoDBManager.collection<UpSiteToAgvSite>().updateOne(UpSiteToAgvSite::upSiteId eq toSite,
              addToSet(UpSiteToAgvSite::agvSiteIds, siteId), UpdateOptions().upsert(true))
          break
        }
      }
    }

    task.persistedVariables["immediate"] = immediate
    task.persistedVariables["toSite"] = toSite
    // 记录当前未完成装舱的station
    task.persistedVariables["stations"] = stations
    task.persistedVariables["toWorkStations"] = toWorkStations
    logger.debug("stations: $stations")
    logger.debug("toWorkStations: $toWorkStations")
//    val stationToMatMap = mutableMapOf<String, Long>()
//    for (station in stations) {
//      val agvSiteInfo = getAgvSiteById(station)
//      if (agvSiteInfo != null) {
//        stationToMatMap[agvSiteInfo.workStation] = agvSiteInfo.matNum.toLong()
//      }
//    }
//    if (stationToMatMap.isNotEmpty()) MongoDBManager.collection<TaskExtraParam>().insertOne(
//        TaskExtraParam(taskId = task.id, stationToMatMap = stationToMatMap)
//    )
    RobotTaskService.saveNewRobotTask(task)


  }

  fun updateAgvSiteInfo(info: AgvSiteInfo) {
    MongoDBManager.collection<AgvSiteInfo>().updateOne(AgvSiteInfo::siteId eq info.siteId,
        set(AgvSiteInfo::workStation setTo info.workStation,
            AgvSiteInfo::matNum setTo info.matNum,
            AgvSiteInfo::state setTo info.state),
        UpdateOptions().upsert(true)
    )
  }

  fun checkPwd(req: StationToPwd): String {
    if (!(CUSTOM_CONFIG.workStations + CUSTOM_CONFIG.addStations).contains(req.workStation)) return "不存在该工位[${req.workStation}]!!"
    val stationToPwd = MongoDBManager.collection<StationToPwd>().findOne(
        StationToPwd::workStation eq req.workStation
    )
    if (stationToPwd == null) {
      MongoDBManager.collection<StationToPwd>().insertOne(StationToPwd(req.workStation, req.workStation, false))
      if (req.pwd.toUpperCase() != req.workStation) return "false"
    } else {
      if (stationToPwd.pwd != req.pwd.toUpperCase()) return "false"
    }
    return "true"
  }

  fun findAvailableAgvSites(req: LoadInfoReq): String {
    val relation = req.relation
    val agvSites = MongoDBManager.collection<AgvSiteInfo>().find().toMutableList()
    val occupied = agvSites.filter { it.matNum > 0 }
    if (occupied.size == 5) return "货舱已被全部占用!!"
    if (5 - occupied.size < relation.size) return "只剩下${5 - occupied.size}个舱位可用!!"

    var needSmallCount = 0
    var needBigCount = 0

    val big = MongoDBManager.collection<AgvSiteInfo>().findOne(
        AgvSiteInfo::siteId eq "B-1")

    val occupiedSmalls = MongoDBManager.collection<AgvSiteInfo>().find(
        and(AgvSiteInfo::siteId `in` listOf("S-1", "S-2", "S-3", "S-4"),
            AgvSiteInfo::matNum gt 0)).toMutableList()

    for (info in relation) {
      if (info.workStation !in (CUSTOM_CONFIG.workStations + CUSTOM_CONFIG.addStations)) return "不存在的工位${info.workStation}!!"
      if (info.matNum == null || info.matNum < 1) return "工位${info.workStation}的料筒数必须大于0!!"
      val siteId = getSiteByWorkStation(info.workStation)
      if (!(siteId != null && siteId != "")) return "${info.workStation}未绑定取料位!!"

      if (info.matNum > 5) return "货舱的料筒数量不能大于5!!"
      if (info.matNum > 2) {
        if (big !=null && big.matNum > 0) return "货舱B-1已被占用，料筒数量不能大于2!!"
        needBigCount++
      } else needSmallCount++
    }
    if (needBigCount > 1) {
      logger.error("料筒数量大于2的舱位只能有一个")
      return "料筒数量大于2的舱位只能有一个"
    }
    val bigForSmall = if (needBigCount > 0 || big?.matNum!! > 0) 0 else 1
    if (needSmallCount > (4 - occupiedSmalls.size + bigForSmall)) return "小货舱${4 - occupiedSmalls.size}个可用!!"

    return ""
  }


  fun listDownSites(): List<String> {
    val downSites = MongoDBManager.collection<StoreSite>()
        .find(StoreSite::type eq "DOWN").toMutableList()
    return mutableListOf<String>().apply {
      for (site in downSites) add(site.id)
    }
  }

  fun listUpSites(): List<String> {
    val upSites = MongoDBManager.collection<StoreSite>()
        .find(StoreSite::type eq "Planner").toMutableList()
    return mutableListOf<String>().apply {
      for (site in upSites) this.add(site.id)
    }
  }

  fun checkSiteToStation(req: SiteToStation, operation: String): String {
    val stations = req.workStations
    for (station in stations) {
      if (!(CUSTOM_CONFIG.workStations + CUSTOM_CONFIG.addStations).contains(station)) return "工位${station}不存在!!"
      if (operation == "add") {
        val stationToSite = MongoDBManager.collection<StationToSite>().findOne(StationToSite::station eq station)
        if (stationToSite != null && !stationToSite.siteId.isEmpty()) return "工位${station}已绑定到${stationToSite.siteId}!!"
      }
      if (operation == "delete") {
        val siteToStation = MongoDBManager.collection<SiteToStation>().findOne(SiteToStation::siteId eq req.siteId)
        if (siteToStation == null || !siteToStation.workStations.contains(station))
          return "工位${station}未绑定到${req.siteId}!!"
      }
    }
    return ""
  }

  fun deleteSiteToStation(req: SiteToStation) {
    for (station in req.workStations) {
      // 更新StationToSite
      MongoDBManager.collection<StationToSite>().updateOne(
          StationToSite::station eq station, set(StationToSite::siteId setTo "")
      )
      // 更新SiteToStation
      MongoDBManager.collection<SiteToStation>().updateOne(
          SiteToStation::siteId eq req.siteId, pull(SiteToStation::workStations, station)
      )
    }
  }

  fun deleteAllSiteToStation(req: SiteIdType) {
    // 更新SiteToStation
      MongoDBManager.collection<SiteToStation>().deleteOne(SiteToStation::siteId eq req.siteId)
//    MongoDBManager.collection<SiteToStation>().updateOne(SiteToStation::siteId eq req.siteId,
//        set(SiteToStation::workStations setTo emptyList()), UpdateOptions().upsert(true))
    // 更新StationToSite
    MongoDBManager.collection<StationToSite>().updateMany(StationToSite::siteId eq req.siteId,
        set(StationToSite::siteId setTo ""), UpdateOptions().upsert(true))
  }

  fun addSiteToStation(req: SiteToStation) {
    for (station in req.workStations) {
      // 更新StationToSite
      MongoDBManager.collection<StationToSite>().updateOne(
          StationToSite::station eq station, set(StationToSite::siteId setTo req.siteId), UpdateOptions().upsert(true)
      )
      // 更新SiteToStation
      MongoDBManager.collection<SiteToStation>().updateOne(
          SiteToStation::siteId eq req.siteId, addToSet(SiteToStation::workStations, station), UpdateOptions().upsert(true)
      )
    }
  }

  fun setTaskInterval(interval: Int) {
    if (interval > 0) {
      DownTask.futureTask.cancel(true)
      MongoDBManager.collection<TaskInterval>().updateOne(TaskInterval::id eq "interval",
          set(TaskInterval::interval setTo interval), UpdateOptions().upsert(true))
      DownTask.futureTask =
          DownTask.downTaskTimer.scheduleAtFixedRate(
              DownTask::createDownTask,
              interval.toLong(),
              interval.toLong(), TimeUnit.MINUTES)
    }
  }

  fun getTaskInterval(): Int {
    val interval = MongoDBManager.collection<TaskInterval>().findOne()
    return interval?.interval ?: CUSTOM_CONFIG.interval
  }

  fun getSiteIdByCurrentPoint(): String {
    val point = VehicleService.listVehicles()[0].currentPosition
    PlantModelService.getPlantModel().locations.values.forEach {
      it.attachedLinks.forEach { link ->
        if (link.point == point)
          return link.location
      }
    }
    return ""
  }
  fun listAgvSites(): List<AgvSiteInfo> {
    return MongoDBManager.collection<AgvSiteInfo>().find().sortedBy { it.siteId }.toMutableList()
  }

  fun getAgvSiteById(siteId: String): AgvSiteInfo? {
    return MongoDBManager.collection<AgvSiteInfo>().findOne(AgvSiteInfo::siteId eq siteId)
  }

  fun getAgvSiteByState(state: Int): List<AgvSiteInfo>? {
    return MongoDBManager.collection<AgvSiteInfo>().find(AgvSiteInfo::state eq state).toMutableList()
  }

//  fun getExtraTaskParamByTaskId(taskId: String): TaskExtraParam? {
//    return MongoDBManager.collection<TaskExtraParam>().findOne(TaskExtraParam::taskId eq taskId)
//  }

  fun listSiteToStation(): List<SiteToStation> {
    return MongoDBManager.collection<SiteToStation>().find().toMutableList()
  }

  fun getSiteByStation(station: String): String {
    val list = MongoDBManager.collection<SiteToStation>().find().toMutableList()
    list.forEach {
      if (it.workStations.contains(station)) return it.siteId
    }
    return ""
  }

//  fun getStationsByAgvSite(siteId: String): List<String>? {
//    return MongoDBManager.collection<SiteToStation>().findOne(SiteToStation::siteId eq siteId)?.workStations
//  }

  fun getSiteByWorkStation(workStation: String): String? {
    return MongoDBManager.collection<StationToSite>().findOne(StationToSite::station eq workStation)?.siteId
  }

  fun getStationsByDownSiteId(siteId: String): List<String> {
    val ws = MongoDBManager.collection<SiteToStation>().findOne(SiteToStation::siteId eq siteId)?.workStations
    return if (ws.isNullOrEmpty()) emptyList() else ws
  }

  @Synchronized
  fun open(siteId: String): String {
//    if (openSiteId == "") openSiteId = siteId
    val openSite = MongoDBManager.collection<OpenSiteId>().findOne()
    val agvSite = getAgvSiteById(siteId)
    if (agvSite?.state != 2) throw BusinessError("${siteId}舱位货物已被取出")
    return when (openSite?.openSiteId) {
      null -> {
        MongoDBManager.collection<OpenSiteId>().insertOne(OpenSiteId(ObjectId(), siteId))
        ""
      }
      "" -> {
        MongoDBManager.collection<OpenSiteId>().updateOne(OpenSiteId::openSiteId eq openSite.openSiteId, set(
          OpenSiteId::openSiteId setTo siteId))
        ""
      }
      else -> {
        logger.error("AGV平板在非目标点进行过操作，请到SRD控制台重置AGV平板!!")
        "等待舱门关闭!!"
      }
    }
//    return if (openSite == null || openSite.openSiteId == "") {
//      MongoDBManager.collection<OpenSiteId>().updateOne(OpenSiteId::openSiteId eq openSite?.openSiteId, set(
//          OpenSiteId::openSiteId setTo siteId), UpdateOptions().upsert(true))
//      ""
//    }
//    else {
//      logger.error("AGV平板在非目标点进行过操作，请到SRD控制台重置AGV平板!!")
//      "操作异常，请重置AGV货舱!!"
//    }
  }

//  fun close(siteId: String) {
//    if (openSiteId != "") openSiteId = ""
//    else logger.error("关闭货仓异常!!")
//  }

  fun resetOpenSiteId() {
    val item = MongoDBManager.collection<OpenSiteId>().findOne()
    if (item != null) MongoDBManager.collection<OpenSiteId>().updateOne(OpenSiteId::id eq item.id,
        set(OpenSiteId::openSiteId setTo ""))
  }

  fun getOpenSiteId(): String {
    return MongoDBManager.collection<OpenSiteId>().findOne()?.openSiteId ?: ""
  }

  fun resetLPS2All() {
    logger.debug("LPS2期重置系统状态...")
    val downSites = Services.listDownSites()
    val upSites = Services.listUpSites()
    val agvSites = Services.listAgvSites()

    MongoDBManager.collection<AgvSiteInfo>().updateMany(AgvSiteInfo::siteId `in` agvSites.map { it.siteId },
        set(AgvSiteInfo::state setTo 0, AgvSiteInfo::workStation setTo "", AgvSiteInfo::matNum setTo 0))

    MongoDBManager.collection<UpSiteToAgvSite>().updateMany(UpSiteToAgvSite::upSiteId `in` upSites,
        set(UpSiteToAgvSite::agvSiteIds setTo emptyList()), UpdateOptions().upsert(true))
    upSiteToAgvSiteMap.clear()

    MongoDBManager.collection<DownSiteToAgvSite>().updateMany(DownSiteToAgvSite::downSiteId `in` downSites,
        set(DownSiteToAgvSite::agvSiteIds setTo emptyList()), UpdateOptions().upsert(true))
    downSiteToAgvSiteMap.clear()

    resetOpenSiteId()

    val tasks = MongoDBManager.collection<RobotTask>().find(RobotTask::state lt RobotTaskState.Success).toMutableList()
    for (task in tasks) {
      if (task.def == CUSTOM_CONFIG.load) {
        try {
          RobotTaskService.abortTask(task.id)
          logger.debug("撤销装舱任务")
        } catch (e: Exception) {
          logger.error("abort task ${task.id}", e)
        }
      }
    }
  }
}

data class StationToSite(
    @BsonId val station: String,
    val siteId: String
)

data class SiteToStation(
    @BsonId val siteId: String,
    val workStations: List<String>
)

data class AgvSiteInfo(
    @BsonId val siteId: String,
    val state: Int = 0,
    val curState: Int = 0,
    val workStation: String = "",
    val matNum: Int = 0,
    val taskId: String = ""
)

data class TaskInterval(
    @BsonId val id: String = "interval",
    val interval: Int
)

data class StationToPwd(
    @BsonId val workStation: String,
    val pwd: String,
    val fire: Boolean
)

data class UpSiteToAgvSite(
    @BsonId val upSiteId: String,
    val agvSiteIds: List<String>
)

data class DownSiteToAgvSite(
    @BsonId val downSiteId: String,
    val agvSiteIds: List<String>
)

data class OpenSiteId(
    @BsonId val id: ObjectId,
    val openSiteId: String
//    val check: Boolean
)

data class MatRecord(
    @BsonId val id: ObjectId = ObjectId(),
    val station: String = "",
    val matNum: Long = 0,
    val recordOn: Instant? = null
)

//data class TaskExtraParam(
//    @BsonId val id: ObjectId = ObjectId(),
//    val taskId: String = "",
//    val stationToMatMap: Map<String, Long> = emptyMap()
//)