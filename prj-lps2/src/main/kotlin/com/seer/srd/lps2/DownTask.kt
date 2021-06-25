package com.seer.srd.lps2

import com.mongodb.BasicDBObject
import com.mongodb.DBCursor
import com.mongodb.client.model.Accumulators
import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.lps2.Services.downSiteToAgvSiteMap
import com.seer.srd.robottask.*
import com.seer.srd.route.service.VehicleService
import com.seer.srd.stats.StatAccount
import com.seer.srd.stats.statAccounts
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object DownTask {

  private val logger = LoggerFactory.getLogger(DownTask::class.java)

  val downTaskTimer = Executors.newScheduledThreadPool(1)!!
  lateinit var futureTask: ScheduledFuture<*>

  private var downChecking = false

  fun init() {
    logger.debug("schedule down task at fixed rate  ...")
    futureTask = downTaskTimer.scheduleAtFixedRate(this::createDownTask, 60, 60, TimeUnit.MINUTES)
  }

  fun dispose() {
    downTaskTimer.shutdown()
  }

  fun createDownTask() {
    logger.debug("try to create down task...")
    synchronized(downTaskTimer){
      if (downChecking) return
        downChecking = true
      try {

        // 检查是否有送料任务
        val existed = MongoDBManager.collection<RobotTask>().findOne(org.litote.kmongo.and(
            RobotTask::state lt RobotTaskState.Success,
            RobotTask::def eq CUSTOM_CONFIG.take
        ))
        if (existed != null) {
          logger.debug("有正在执行的取料任务, 跳过此次创建送料任务")
          return
        }

        // 检查是否有立即执行的装舱任务
//        val existed2 = MongoDBManager.collection<RobotTask>().findOne(org.litote.kmongo.and(
//            RobotTask::state lt RobotTaskState.Success,
//            RobotTask::def eq "load2"
//        ))
//        if (existed2 != null && existed2.persistedVariables["immediate"] as Boolean) {
//          logger.debug("有立即执行的任务")
//          return
//        }
        // 检查当前AGV是不是有单
        val vehicle = VehicleService.listVehicles()[0]
        if (!vehicle.transportOrder.isNullOrBlank() || !vehicle.orderSequence.isNullOrBlank()) {
          logger.debug("AGV正在执行装舱任务, 跳过此次创建送料任务")
          return
        }


        // 检查是否有要送料的货舱
        val agvSites = Services.listAgvSites()
        var takeNum = 0
        for (site in agvSites) {
          if (site.state == 2) takeNum++
        }
        if (takeNum == 0) return

        createTask() // 为防止重复重建，数据库存储完成后再改 upChecking

      } catch (e: Exception) {
        logger.debug("create down task error", e)
      } finally {
        downChecking = false
      }
    }
  }

  @Synchronized
  fun createTask() {
    // 检查有几个不同的目标点
    val agvSitesFull = Services.getAgvSiteByState(2)
    if (agvSitesFull.isNullOrEmpty()) return
    val agvSites = agvSitesFull.distinctBy {
      Services.getSiteByWorkStation(it.workStation)
    }
    if (agvSites.isNullOrEmpty()) return

    val actual = sortStations(agvSites)
    logger.debug("工作站顺序：${actual.map { Services.getSiteByStation(it) }}")

    val def = getRobotTaskDef(CUSTOM_CONFIG.take) ?: throw BusinessError("NoSuchTaskDef: ${CUSTOM_CONFIG.take}")
    val task = buildTaskInstanceByDef(def)
    task.priority = 10
    val infoMap= agvSitesFull.map { LinkedHashMap<String, Any>().apply {
      this["siteId"] = it.siteId
      this["state"] = it.state
      this["curState"] = it.curState
      this["matNum"] = it.matNum
      this["taskId"] = it.taskId
      this["workStation"] = it.workStation
    } }
    task.persistedVariables["toWorkStations"] = infoMap
    for (i in 1..5) {
      task.persistedVariables["toSite$i"] = ""
    }
    var i = 0
    var index = 1
//    for (site in agvSites) {
//    val stationToMatMap = mutableMapOf<String, Long>()
    for (site in actual) {
      i++
      if (i > 1) index += 32
      val siteId = Services.getSiteByWorkStation(site) ?: return
      task.transports[index].stages[0].location = siteId
      task.transports[index].stages[1].location = siteId
      task.transports[index].stages[2].location = siteId
      task.persistedVariables["toSite$i"] = siteId

      // 更新DownSiteToAgvSite
      // 先找该位置绑定的工位
      val stations = Services.getStationsByDownSiteId(siteId)
      // 当前等待取料的舱位
      val allAgvSites = Services.getAgvSiteByState(2)!!
      for (s in allAgvSites) {
        if (s.workStation in stations) {
          if (downSiteToAgvSiteMap[siteId].isNullOrEmpty()) downSiteToAgvSiteMap[siteId] = listOf(s.siteId)
          else downSiteToAgvSiteMap[siteId] = downSiteToAgvSiteMap[siteId]!!.toMutableList().let {
            it.add(s.siteId)
            it
          }
          MongoDBManager.collection<DownSiteToAgvSite>().updateOne(DownSiteToAgvSite::downSiteId eq siteId,
              addToSet(DownSiteToAgvSite::agvSiteIds, s.siteId), UpdateOptions().upsert(true))
        }
      }
    }
    RobotTaskService.saveNewRobotTask(task)
  }

  //agvSites是已经过滤了(目标工作库位相同的)工位
  private fun sortStations(agvSites: List<AgvSiteInfo>?): List<String> {

    logger.debug("计算排序顺序")
    var stationList = agvSites?.map { it.workStation }?.toMutableList() ?: return emptyList()

    val left312 = mutableListOf<String>()
    val left314 = mutableListOf<String>()
    val right312 = mutableListOf<String>()
    val right314 = mutableListOf<String>()


    CUSTOM_CONFIG.left312.forEach {
      if (stationList.contains(it)) {
        left312.add(it)
        val index = stationList.indexOf(it)
        stationList.removeAt(index)
        if (!stationList.contains("312ZZ")) stationList.add(index, "312ZZ")
      }
    }

    CUSTOM_CONFIG.left314.forEach {
      if (stationList.contains(it)) {
        left314.add(it)
        val index = stationList.indexOf(it)
        stationList.removeAt(index)
        if (!stationList.contains("315-A")) stationList.add(index, "315-A")
      }
    }

    CUSTOM_CONFIG.right312.forEach {
      if (stationList.contains(it)) {
        right312.add(it)
        val index = stationList.indexOf(it)
        stationList.removeAt(index)
        if (!stationList.contains("313ZZ")) stationList.add(index, "313ZZ")
      }
    }

    CUSTOM_CONFIG.right314.forEach {
      if (stationList.contains(it)) {
        right314.add(it)
        val index = stationList.indexOf(it)
        stationList.removeAt(index)
        if (!stationList.contains("314-A")) stationList.add(index, "314-A")
      }
    }

//    stationList.sortBy { it }
    stationList.sortBy {
      val toSite = Services.getSiteByWorkStation(it)
      when (toSite) {
        null -> it
        else -> toSite
      }
    }

    var list1 = stationList.filter { it.startsWith("311") }
    val list2 = stationList.filter { it.startsWith("312") }
    var list3 = stationList.filter { it.startsWith("313") }
    val list4 = stationList.filter { it.startsWith("314") }
    var list5 = stationList.filter { it.startsWith("315") }

    if (CUSTOM_CONFIG.descending) {

      if (!list3.isEmpty()) list3 = list3.reversed()
      stationList = (list5 + list4 + list3 + list2 + list1).toMutableList()

    } else {

      if (!list5.isEmpty()) list5 = list5.reversed()
      if (!list2.isEmpty()) list1 = list1.reversed()
      if (list4.isEmpty() && list5.isEmpty()) {
        if (list2.isEmpty()) list3 = list3.reversed()
      }
      else {
        if (list2.isEmpty()) {
          if (list3.contains("313ZZ")) {
            val tempList3 = list3.toMutableList()
            tempList3.remove("313ZZ")
            tempList3.add(0, "313ZZ")
            list3 = tempList3
          }
        }
      }
      stationList = (list1 + list2 + list3 + list4 + list5).toMutableList()

    }

    if (stationList.contains("315-A")) {
      val index = stationList.indexOf("315-A")
      stationList.removeAt(index)
      left314.forEach {
        stationList.add(index, it)
      }
    }

    if (stationList.contains("314-A")) {
      val index = stationList.indexOf("314-A")
      stationList.removeAt(index)
      right314.forEach {
        stationList.add(index, it)
      }
    }

    if (stationList.contains("313ZZ")) {
      val index = stationList.indexOf("313ZZ")
      stationList.removeAt(index)
      right312.forEach {
        stationList.add(index, it)
      }
//      stationList.add(index, "312-RIGHT")
    }

    if (stationList.contains("312ZZ")) {
      val index = stationList.indexOf("312ZZ")
      stationList.removeAt(index)
      left312.forEach {
        stationList.add(index, it)
      }
//      stationList.add(index, "312-RIGHT")
    }

    if (stationList.contains("311U")) {
      stationList.remove("311U")
      if (CUSTOM_CONFIG.descending) stationList.add("311U")
      else stationList.add(0, "311U")
    }
    return stationList
  }

  @Synchronized
  fun createTask2() {
    // 检查有几个不同的目标点
    val agvSites = Services.getAgvSiteByState(2)?.distinctBy {
      Services.getSiteByWorkStation(it.workStation)
    }?.sortedByDescending { it.workStation }

    if (agvSites.isNullOrEmpty()) return

    val actual = agvSites.map { it.workStation }.toMutableList()
    if (actual.contains("311U")) {
      actual.remove("311U")
      actual.add("311U")
    }

//    for (site in agvSites) {
    for (site in actual) {

      val def = getRobotTaskDef("take3") ?: throw BusinessError("NoSuchTaskDef: 'take3'")
      val task = buildTaskInstanceByDef(def)
      val siteId = Services.getSiteByWorkStation(site) ?: return
      task.transports[1].stages[0].location = siteId
      task.transports[1].stages[1].location = siteId
      task.transports[1].stages[2].location = siteId
      task.persistedVariables["toSite"] = siteId

      // 更新DownSiteToAgvSite
      // 先找该位置绑定的工位
      val stations = Services.getStationsByDownSiteId(siteId)
      // 当前等待取料的舱位
      val allAgvSites = Services.getAgvSiteByState(2)!!
      for (s in allAgvSites) {
        if (s.workStation in stations) {
          if (downSiteToAgvSiteMap[siteId].isNullOrEmpty()) downSiteToAgvSiteMap[siteId] = listOf(s.siteId)
          else downSiteToAgvSiteMap[siteId] = downSiteToAgvSiteMap[siteId]!!.toMutableList().let {
            it.add(s.siteId)
            it
          }
          MongoDBManager.collection<DownSiteToAgvSite>().updateOne(DownSiteToAgvSite::downSiteId eq siteId,
              addToSet(DownSiteToAgvSite::agvSiteIds, s.siteId), UpdateOptions().upsert(true))
        }
      }

      task.transports[1].deadline = task.createdOn

      RobotTaskService.saveNewRobotTask(task)
      logger.debug("create down task success, to $siteId deadline: ${task.transports[1].deadline}")
      Thread.sleep(4000)
    }
  }

}