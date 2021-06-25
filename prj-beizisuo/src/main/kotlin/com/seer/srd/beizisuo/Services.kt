package com.seer.srd.beizisuo

import com.seer.srd.beizisuo.plc.MyTcpServer
import com.seer.srd.beizisuo.plc.MyTcpServer.socketMap
import com.seer.srd.beizisuo.plc.tcpPkgHead
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.route.getVehicleDetailsByName
import com.seer.srd.route.service.TransportOrderService
import com.seer.srd.route.service.VehicleService
import com.seer.srd.vehicle.Vehicle
import org.litote.kmongo.lt
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

object Services {

  private val logger = LoggerFactory.getLogger(Services::class.java)

  @Volatile
  var curErrorCode = 0

  private val executor = Executors.newSingleThreadExecutor()


  fun init() {
    executor.submit {
      while (true) {
        Thread.sleep(2000)
        try {
          if (socketMap.isEmpty()) continue
          val vs = VehicleService.listVehicles()
          if (vs.isNotEmpty()) {
          val v = vs[0]
          val errorCode = if (v.state == Vehicle.State.ERROR) {
            val details = getVehicleDetailsByName(v.name)
            val newAdapter = CUSTOM_CONFIG.newTcpAdapter
            var fatals = emptyList<Any>()
            var errors = emptyList<Any>()
            var warnings = emptyList<Any>()
            if (!newAdapter) {
              val errorInfo = details?.get("errorinfo") as List<Map<String, Any>>? ?: emptyList()
              if (errorInfo.isNotEmpty()) {
                fatals = errorInfo.filter { it["level"] == "Fatal" }
                errors = errorInfo.filter { it["level"] == "Error" }
                warnings = errorInfo.filter { it["level"] == "Warning" }
              }
            } else {
              fatals = details?.get("fatals") as List<Map<String, Any>>? ?: emptyList()
              errors = details?.get("errors") as List<Map<String, Any>>? ?: emptyList()
              warnings = details?.get("warnings") as List<Map<String, Any>>? ?: emptyList()
            }
            when {
              fatals.isNotEmpty() -> {
                val fatal = fatals[0] as Map<String, Any>
                if (!newAdapter) fatal["code"] as Int else fatal.map { it.key }[0] as Int

              }
              errors.isNotEmpty() -> {
                val error = errors[0] as Map<String, Any>
                if (!newAdapter) error["code"] as Int else error.map { it.key }[0] as Int
              }
              warnings.isNotEmpty() -> {
                val warning = warnings[0] as Map<String, Any>
                if (!newAdapter) warning["code"] as Int else warning.map { it.key }[0] as Int
              }
              else -> 0
            }
          } else 0

          // 55001为warning，警告信息为AGV控制权在调度系统
          if (curErrorCode == errorCode) continue
          curErrorCode = errorCode

          var taskType = 0
          val onPositionFrom = 0
          val onPositionTo = 0
          var executing = 0

          val status = when (v.state) {
            Vehicle.State.IDLE -> 0
            Vehicle.State.EXECUTING -> 1
            Vehicle.State.ERROR -> 2
            Vehicle.State.CHARGING -> 3
            else -> 4
          }

          val transportId = v.transportOrder
          if (!transportId.isNullOrBlank()) {
            val transport = TransportOrderService.getOrderOrNull(transportId)
            if (transport != null) {
              if (!transport.isDispensable) {
                executing = 1
                val unfinishedTasks = MongoDBManager.collection<RobotTask>().find(RobotTask::state lt RobotTaskState.Success).toMutableList()
                for (task in unfinishedTasks) {
                  if (task.transports.map { it.routeOrderName }.contains(transportId)) {
                    taskType = task.persistedVariables["taskType"] as Int? ?: 0
                  }
                }
              }
            }
          }
          val buffer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
          buffer.put(tcpPkgHead)
          buffer.put(9.toByte())
          buffer.put(taskType.toByte())
          buffer.put(onPositionFrom.toByte())
          buffer.put(onPositionTo.toByte())
          buffer.put(executing.toByte())
          buffer.put(status.toByte())
          buffer.putInt(errorCode)

          val byteArray = buffer.array()
          MyTcpServer.write(socketMap.map { it.key }[0], byteArray, true)
          }
        } catch (e: Exception) {
          logger.error("report AGV state error", e)
        }
      }
    }
  }
}










