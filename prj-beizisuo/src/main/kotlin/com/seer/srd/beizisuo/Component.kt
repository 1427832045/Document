package com.seer.srd.beizisuo

import com.seer.srd.BusinessError
import com.seer.srd.beizisuo.Services.curErrorCode
import com.seer.srd.beizisuo.plc.MyTcpServer
import com.seer.srd.beizisuo.plc.tcpPkgHead
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.route.getVehicleDetailsByName
import com.seer.srd.route.service.VehicleService
import com.seer.srd.vehicle.Vehicle
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ExtraComponent {

  private val logger = LoggerFactory.getLogger(ExtraComponent::class.java)


  val extraComponents: List<TaskComponentDef> = listOf(
      TaskComponentDef(
          "extra", "bzs:isFinished", "检查是否完成抓取", "", false, listOf(
          TaskComponentParam("type", "from/to", "string")
      ), false) { component, ctx ->
        val type = parseComponentParamValue("type", component, ctx) as String
        val finished = when (type) {
          "from" -> {
            val t = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq ctx.task.id) ?: throw BusinessError("task id ${ctx.task.id} not exits")
            t.persistedVariables["loaded"] as Boolean
          }
          "to" -> {
            val t = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq ctx.task.id) ?: throw BusinessError("task id ${ctx.task.id} not exits")
            t.persistedVariables["unloaded"] as Boolean
          }
          else -> throw BusinessError("unknown string $type")
        }
        if (!finished) throw BusinessError("等待手臂动作完成")
        logger.debug("arm task finished")
      },
      TaskComponentDef(
          "extra", "bzs:onPosition", "AGV到位/离开", "", false, listOf(
          TaskComponentParam("type", "from/to", "string"),
          TaskComponentParam("onPosition", "0/1", "int")
      ), false) { component, ctx ->
        val remoteAddr = ctx.task.persistedVariables["remoteAddr"] as String? ?: throw BusinessError("获取PLC信息失败")

        val type = parseComponentParamValue("type", component, ctx) as String
        val taskType = ctx.task.persistedVariables["taskType"] as Int? ?: throw BusinessError("获取任务类型失败!")
        val onPosition = parseComponentParamValue("onPosition", component, ctx) as Int

        var onPositionFrom = 0
        var onPositionTo = 0
        when (type) {
          "from" -> onPositionFrom = CUSTOM_CONFIG.taskTypeMap[taskType]?.from ?: throw BusinessError("获取起点失败")
          "to" -> onPositionTo = CUSTOM_CONFIG.taskTypeMap[taskType]?.to ?: throw BusinessError("获取起点失败")
          else -> throw BusinessError("获取任务类型失败!!")
        }
        // AGV是否在执行任务
        val executing = 1

        val vehicles = VehicleService.listVehicles()
        if (vehicles.isEmpty()) throw BusinessError("AGV信息获取失败")
        // AGV状态
        val status = if (onPosition == 0) 0 else 1
        val errorCode = 0     // 异常errorCode处理放到Service线程池中
        curErrorCode = errorCode
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
        MyTcpServer.write(remoteAddr, byteArray, true)
        logger.debug("write to plc: ${byteArray.toList()}")
      }
  )
}
