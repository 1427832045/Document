package com.seer.srd.huaxin

import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.RobotTask
import com.seer.srd.route.service.PlantModelService
import com.seer.srd.vehicle.Vehicle
import org.litote.kmongo.eq
import org.litote.kmongo.exists
import org.litote.kmongo.set
import org.litote.kmongo.setTo
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object Services {

  private val logger = LoggerFactory.getLogger("heFei-services")

  @Volatile
  var checkFrom = false

  val checkFromMap: MutableMap<String, Boolean> = ConcurrentHashMap()

  @Volatile
  var checkTo = false

  val checkToMap: MutableMap<String, Boolean> = ConcurrentHashMap()

  @Volatile
  var go: Boolean = false

  val checkGoMap: MutableMap<String, Boolean> = ConcurrentHashMap()

  @Volatile
  var end: Boolean = false

  val checkEndMap: MutableMap<String, Boolean> = ConcurrentHashMap()

  @Volatile
  var workStation = ""

  private val workStationMap: MutableMap<String, String> = ConcurrentHashMap()

  init {

  }

//  fun init() {
//    (CUSTOM_CONFIG.taskDefs + CUSTOM_CONFIG.extraTaskDefs).forEach {
//
//    }
//  }

  fun listTaskToSite(): Map<String, List<SiteToVehicle>> {
    return CUSTOM_CONFIG.taskDefToSiteVehicle + CUSTOM_CONFIG.extraTaskDefToSiteVehicle
  }

  fun letItGo(v: Vehicle) {
    if (v.state != Vehicle.State.IDLE && v.state != Vehicle.State.EXECUTING) throw BusinessError("${v.name}非调度状态！！")
    if (workStationMap[v.name].isNullOrBlank()) throw BusinessError("运行${v.name}失败！！")
    val point = v.currentPosition
    var location = ""
    PlantModelService.getPlantModel().locations.values.forEach {
      it.attachedLinks.forEach { link ->
        if (link.point == point) location = link.location
      }
    }
    if (!location.isBlank() && location != workStationMap[v.name]) throw BusinessError("${v.name}未到达位置【${workStationMap[v.name]}】！！")
    if (checkFromMap[v.name] != true) throw BusinessError("运行${v.name}失败！")
    if (checkGoMap[v.name] == true) throw BusinessError("${v.name}已运行！！")
    else checkGoMap[v.name] = true
  }

  fun endItUp(v: Vehicle) {
    val state = v.state
    if (state != Vehicle.State.IDLE && state != Vehicle.State.EXECUTING) throw BusinessError("${v.name}非调度状态！！")
    if (workStationMap[v.name].isNullOrBlank()) throw BusinessError("${v.name}归位失败！！")
    val point = v.currentPosition
    var location = ""
    PlantModelService.getPlantModel().locations.values.forEach {
      it.attachedLinks.forEach { link ->
        if (link.point == point) location = link.location
      }
    }
    if (!location.isBlank() && location != workStationMap[v.name]) throw BusinessError("${v.name}未到达位置【${workStationMap[v.name]}】！！")
    if (checkToMap[v.name] != true) throw BusinessError("${v.name}归位失败！")
    if (checkEndMap[v.name] == true) throw BusinessError("${v.name}已归位！！")
    else checkEndMap[v.name] = true
  }

  fun onRobotTaskFinished(task: RobotTask) {
    if(task.def in (CUSTOM_CONFIG.taskDefs + CUSTOM_CONFIG.extraTaskDefs)) {
      val vehicleName = task.persistedVariables["vehicleName"] as String?
      if (!vehicleName.isNullOrBlank()) {
        checkGoMap[vehicleName] = false
        checkEndMap[vehicleName] = false
        checkFromMap[vehicleName] = false
        checkToMap[vehicleName] = false
        workStationMap[vehicleName] = ""
        MongoDBManager.collection<TaskWorkStation>().updateOne(TaskWorkStation::taskId eq task.id,
            set(TaskWorkStation::taskId setTo "", TaskWorkStation::workStation setTo ""))
      } else {
        logger.warn("no vehicleName for ${task.def} taskId: ${task.id}")
      }
    }
  }

  fun onRobotTaskUpdated(task: RobotTask) {
    if(task.def in (CUSTOM_CONFIG.taskDefs + CUSTOM_CONFIG.extraTaskDefs)) {
      if (task.transports[1].processingRobot != null) {
        workStationMap[task.transports[1].processingRobot!!] = task.transports[1].stages[2].location
        MongoDBManager.collection<TaskWorkStation>().updateOne(TaskWorkStation::id exists true,
            set(TaskWorkStation::taskId setTo task.id, TaskWorkStation::workStation setTo workStationMap[task.transports[1].processingRobot!!]))
      }
      else if (task.transports[0].processingRobot != null) {
        workStationMap[task.transports[0].processingRobot!!] = task.transports[0].stages[2].location
        MongoDBManager.collection<TaskWorkStation>().updateOne(TaskWorkStation::id exists true,
            set(TaskWorkStation::taskId setTo task.id, TaskWorkStation::workStation setTo workStationMap[task.transports[0].processingRobot!!]))
      }
    }
  }
}