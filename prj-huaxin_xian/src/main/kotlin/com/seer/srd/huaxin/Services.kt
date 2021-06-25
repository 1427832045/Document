package com.seer.srd.huaxin

import com.seer.srd.BusinessError
import com.seer.srd.Error400
import com.seer.srd.robottask.*
import com.seer.srd.route.service.PlantModelService
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.util.HttpClient.buildHttpClient
import com.seer.srd.vehicle.Vehicle
import org.slf4j.LoggerFactory
import retrofit2.Call
import retrofit2.http.*
import java.lang.Exception
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

object Services {

  private val logger = LoggerFactory.getLogger("xian-services")

  val checkFromMap: MutableMap<String, Boolean> = ConcurrentHashMap()

  val checkToMap: MutableMap<String, Boolean> = ConcurrentHashMap()

  val checkGoMap: MutableMap<String, Boolean> = ConcurrentHashMap()

  val checkEndMap: MutableMap<String, Boolean> = ConcurrentHashMap()

  private val workStationMap: MutableMap<String, String> = ConcurrentHashMap()

  val mesHttpClient = buildHttpClient(CUSTOM_CONFIG.mesUrl, MesHttpClient::class.java)

  val waitingPassMap: MutableMap<String, Boolean> = ConcurrentHashMap()

  val passLocations: MutableSet<String> = CopyOnWriteArraySet()

  val waitingRolledMap: MutableMap<String, Boolean> = ConcurrentHashMap()

  val rolledLocations: MutableSet<String> = CopyOnWriteArraySet()

  init {
    logger.debug("...huaxin-xian...")
    waitingRolledMap.putAll(mapOf("LK1A" to false, "LK1B" to false, "LK1C" to false))
    waitingPassMap.putAll(mapOf("LK1A" to false, "LK1B" to false, "LK1C" to false))
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
//      val vehicleName = task.persistedVariables["vehicleName"] as String?
      val from = task.persistedVariables["from"] as String
      val vehicleName = when {
        task.def.contains("2F_") -> {
          task.transports[0].processingRobot
        }
        task.def == "ClearToElectricMaterialFix" -> {
          task.transports[0].processingRobot
        }
        else -> {
          if (from.contains("LK")) task.transports[0].processingRobot
          else task.transports[4].processingRobot
        }
      }
      if (!vehicleName.isNullOrBlank()) {
        checkGoMap[vehicleName] = false
        checkEndMap[vehicleName] = false
        checkFromMap[vehicleName] = false
        checkToMap[vehicleName] = false
        workStationMap[vehicleName] = ""
      } else {
        logger.warn("no vehicleName for ${task.def} taskId: ${task.id}")
      }
    }
  }

  fun onRobotTaskUpdated(task: RobotTask) {
    if(task.def in (CUSTOM_CONFIG.taskDefs + CUSTOM_CONFIG.extraTaskDefs)) {
      if (task.def.contains("2F_")) {
        if (task.def.contains("ASToRelayCheck") || task.def.contains("ASToLine")) {
          when {
            task.transports[3].processingRobot != null -> workStationMap[task.transports[2].processingRobot!!] = task.transports[3].stages[1].location
            task.transports[2].processingRobot != null -> workStationMap[task.transports[2].processingRobot!!] = task.transports[2].stages[2].location
            task.transports[1].processingRobot != null -> workStationMap[task.transports[1].processingRobot!!] = task.transports[1].stages[3].location
            task.transports[0].processingRobot != null -> workStationMap[task.transports[0].processingRobot!!] = task.transports[0].stages[2].location
          }
        } else {
          if (task.transports[1].processingRobot != null) {
            workStationMap[task.transports[1].processingRobot!!] = task.transports[1].stages[2].location
          }
          else if (task.transports[0].processingRobot != null) {
            workStationMap[task.transports[0].processingRobot!!] = task.transports[0].stages[2].location
          }
        }
      }
      if (task.def.contains("1F_")) {
        try {
          if (task.def.contains("AST")) {
            val from = task.persistedVariables["from"] as String
            if (from.contains("LK")) {
              if (task.transports[2].processingRobot != null)
                workStationMap[task.transports[2].processingRobot!!] = task.transports[2].stages[3].location
            } else {
              if (task.transports[4].processingRobot != null)
                workStationMap[task.transports[4].processingRobot!!] = task.transports[4].stages[2].location
            }
          } else {
            if (task.transports[1].processingRobot != null) {
              workStationMap[task.transports[1].processingRobot!!] = task.transports[1].stages[2].location
            }
            else if (task.transports[0].processingRobot != null) {
              workStationMap[task.transports[0].processingRobot!!] = task.transports[0].stages[2].location
            }
          }
        } catch (e: Exception) {
          logger.error("onRobotTaskUpdated Error", e)
        }
      }
    }
  }
  fun createTask(from: String, to: String, defName: String): String {
    if (from.isNotBlank() && to.isNotBlank()) throw Error400("Error", "接口错误")
    StoreSiteService.getStoreSiteById(from) ?: throw Error400("NoSuchStoreSite", from)
    StoreSiteService.getStoreSiteById(to) ?: throw Error400("NoSuchStoreSite", to)
    val def = getRobotTaskDef(defName) ?: throw Error400("NoSuchDef", defName)
    val task = buildTaskInstanceByDef(def)
    if (from.contains("LK")) {
      RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, "立库起点", task.transports[4], task)
      RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, "立库起点", task.transports[5], task)
      RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, "立库起点", task.transports[6], task)
      RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, "立库起点", task.transports[7], task)
      task.transports[0].stages[2].location = from
      task.transports[1].stages[1].location = from
      task.transports[2].stages[3].location = to
      task.transports[3].stages[2].location = to
    } else {
      RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, "立库终点", task.transports[0], task)
      RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, "立库终点", task.transports[1], task)
      RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, "立库终点", task.transports[2], task)
      RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, "立库终点", task.transports[3], task)
      task.transports[4].stages[2].location = from
      task.transports[5].stages[2].location = to
      task.transports[6].stages[2].location = to
      task.transports[7].stages[3].location = to
    }
    task.persistedVariables["from"] = from
    task.persistedVariables["to"] = to
    RobotTaskService.saveNewRobotTask(task)
    return task.id
  }

  fun getStationIdByLocation(location: String): String {
    return when (location) {
      "LK1A" -> "1"
      "LK1B" -> "3"
      "LK1C" -> "2"
      else -> ""
    }
  }

  fun getLocationById(station: String): String {
    return when (station) {
      "1" -> "LK1A"
      "3" -> "LK1B"
      "2" -> "LK1C"
      else -> ""
    }
  }
}

@JvmSuppressWildcards
interface MesHttpClient {
  // SRD 请求上位机，已到位，可以开始转动辊筒
  @POST("seer/outstockbegin")
  fun outReady(@Query("station") station: String): Call<String>
//  fun outReady(@Body req: MesReq): Call<Void>

  // SRD 请求上位机，已装载,可以停止转动辊筒
  @POST("seer/outstockend")
  fun outLoaded(@Query("station") station: String): Call<String>
//  fun outLoaded(@Body req: MesReq): Call<Void>

  // SRD 请求上位机，已到位
  @POST("seer/instockbegin")
  fun inReady(@Query("station") station: String): Call<String>
//  fun inReady(@Body req: MesReq): Call<Void>

  // SRD 请求上位机，已卸载
  @POST("seer/1F/in/agv-unloaded")
  fun inUnloaded(@Query("station") station: String): Call<String>
//  fun inUnloaded(): Call<Void>

}

data class MesResponse(
    val code: Int,
    val message: String
)