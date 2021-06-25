package com.seer.srd.chinapost

import com.seer.srd.BusinessError
import com.seer.srd.Error400
import com.seer.srd.chinapost.ChinaPostApp.modBusHelpers
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.RobotStage
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTransport
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import org.litote.kmongo.`in`
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.slf4j.LoggerFactory
import java.time.Instant

object Services {

  private val logger = LoggerFactory.getLogger(Services::class.java)

  fun getSiteList(): List<NewSite> {
    val sites = MongoDBManager.collection<StoreSite>().find().toList()
    return toListSite(sites)
  }

  fun getWMSTaskList(): List<NewTask> {
    val tasks = MongoDBManager.collection<RobotTask>().find(
        RobotTask::def `in` listOf("fullTray2Roller", "fullTrayTransfer")
    ).toList()
    return toListTask(tasks)
  }

  fun getWMSTaskById(id: String): NewTask {
    val task = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq id) ?: throw Error400("NoSuchTaskId", "任务[$id]不存在")
    return toListTask(listOf(task))[0]
  }

  fun occupy(siteMap: Map<String, Boolean>) {
    val siteIds = siteMap.filter { it.value }.map { it.key }
    StoreSiteService.changeSitesFilledByIds(siteIds, true, "PC端:占用")
  }

  fun release(siteMap: Map<String, Boolean>) {
    val siteIds = siteMap.filter { it.value }.map { it.key }
    StoreSiteService.changeSitesFilledByIds(siteIds, true, "PC端:未占用")
  }

  @Synchronized
  fun getModBusSiteBySiteId(siteId: String, enabled: Boolean): StoreSite {
    if (enabled) {
      CUSTOM_CONFIG.area.forEach {
        val siteToAddr = it.value.siteToAddr
        val areaName = it.key
        if (siteToAddr.keys.contains(siteId)) {
          val addr = siteToAddr[siteId] ?: throw BusinessError("$areaName no such [$siteId] config")
          val value = modBusHelpers[areaName]?.read02DiscreteInputs(addr, 1, 1, siteId)?.getByte(0)?.toInt() ?: throw BusinessError("read $siteId error")
          val site = StoreSiteService.getExistedStoreSiteById(siteId)
          if (site.filled && value % 2 == 0) StoreSiteService.changeSiteFilled(siteId, false, "changed from modbus")
          if (!site.filled && value % 2 != 0) StoreSiteService.changeSiteFilled(siteId, true, "changed from modbus")
          return StoreSiteService.getExistedStoreSiteById(siteId)
        }
      }
    }
    return StoreSiteService.getExistedStoreSiteById(siteId)
//    val area = CUSTOM_CONFIG.area[areaName] ?: throw BusinessError("no such [$areaName] config")
//    val addr = area.siteToAddr[siteId] ?: throw BusinessError("$areaName no such [$siteId] config")
//    val value = modBusHelpers[areaName]?.read02DiscreteInputs(addr, 1, 1, siteId)?.getByte(0)?.toInt() ?: throw BusinessError("read $siteId error")
//    val site = StoreSiteService.getExistedStoreSiteById(siteId)
//    if (site.filled && value == 0) StoreSiteService.changeSiteFilled(siteId, false, "changed from modbus")
//    if (!site.filled && value != 0) StoreSiteService.changeSiteFilled(siteId, true, "changed from modbus")
//    return StoreSiteService.getExistedStoreSiteById(siteId)
  }

  fun updateSiteByArea(areaName: String, enabled: Boolean): List<StoreSite> {
    val sites = mutableListOf<StoreSite>()
    if (enabled) {
      val helper = modBusHelpers[areaName] ?: throw BusinessError("$areaName modbus connect error")
      val area = CUSTOM_CONFIG.area[areaName] ?: throw BusinessError("no such [$areaName] config")
      area.siteToAddr.forEach {
        val siteId = it.key
        val addr = it.value
        val value = helper.read02DiscreteInputs(addr, 1, 1, siteId)?.getByte(0)?.toInt() ?: throw BusinessError("read $siteId error")
        val site = StoreSiteService.getExistedStoreSiteById(siteId)
        if (site.filled && value % 2 == 0) StoreSiteService.changeSiteFilled(siteId, false, "changed from modbus")
        if (!site.filled && value % 2 != 0) StoreSiteService.changeSiteFilled(siteId, true, "changed from modbus")
        sites.add(StoreSiteService.getExistedStoreSiteById(siteId))
      }
    } else {
      sites.addAll(StoreSiteService.listStoreSites().filter { it.type == "码托库位" })
    }
    return sites
  }

  private fun toListSite(sites: List<StoreSite>): List<NewSite> {
    return sites.map { NewSite(id = it.id, type = it.type, filled = it.filled, content = it.content, locked = it.locked) }
  }

  private fun toListTask(tasks: List<RobotTask>): List<NewTask> {
    return tasks.map {
      NewTask(
          taskId = it.id,
          def = it.def,
          createdOn = it.createdOn,
          finishedOn = it.finishedOn,
          state = it.state,
          transports = toListTransport(it.transports)
      )
    }
  }

  private fun toListTransport(transports: List<RobotTransport>): List<NewTaskTransport> {
    return transports.map { NewTaskTransport(
        taskId = it.taskId,
        routeOrderName = it.routeOrderName,
        intendedRobot = it.intendedRobot,
        processingRobot = it.processingRobot,
        processingRobotAssignedOn = it.processingRobotAssignedOn,
        state = it.state,
        stages = toListStage(it.stages)
    ) }
  }

  private fun toListStage(stages: List<RobotStage>): List<NewStage> {
    return stages.map { NewStage(state = it.state, location = it.location, operation = it.operation, properties = it.properties) }
  }
}

data class NewSite(
    val id: String,
    val type: String,
    val filled: Boolean,
    val content: String,
    val locked: Boolean
)

data class NewTask(
    val taskId: String,
    val def: String,
    val createdOn: Instant,
    val finishedOn: Instant?,
    val state: Int,
    val transports: List<NewTaskTransport> = emptyList()
)

data class NewTaskTransport(
    val taskId: String,
    val routeOrderName: String,
    val intendedRobot: String?,
    val processingRobot: String?,
    val processingRobotAssignedOn: Instant?,
    val state: Int,
    val stages: List<NewStage> = emptyList()
)

data class NewStage(
    val state: Int,
    val location: String,
    var operation: String,
    var properties: String
)

data class UpdateSiteReq(
    val params: Map<String, Boolean>
)

//data class SiteReq(
//    val siteId: String,
//    val changed: Boolean
//)