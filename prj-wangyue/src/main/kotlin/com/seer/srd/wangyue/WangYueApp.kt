package com.seer.srd.wangyue

import com.seer.srd.Application
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.eventbus.EventBus
import com.seer.srd.robottask.*
import com.seer.srd.robottask.component.getHelper
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.setVersion
import com.seer.srd.wangyue.ExtraComponents.extraComponent
import org.litote.kmongo.lt
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun main() {
  setVersion("望月", "1.0.0")

  Application.initialize()

  registerRobotTaskComponents(extraComponent)

  EventBus.robotTaskCreatedEventBus.add(WangYueApp::onRobotTaskCreated)
  EventBus.robotTaskUpdatedEventBus.add(WangYueApp::onRobotTaskUpdated)
  EventBus.robotTaskFinishedEventBus.add(WangYueApp::onRobotTaskFinished)

  Application.start()

  WangYueApp.init()
}

object WangYueApp {

  private val logger = LoggerFactory.getLogger(WangYueApp::class.java)

  private val executor = Executors.newScheduledThreadPool(1)

  private val helper = getHelper(CUSTOM_CONFIG.host, CUSTOM_CONFIG.port)

  @Volatile
  var flag = false

  val unfinishedTasks = ConcurrentHashMap<String, RobotTask>()

  fun init() {
    logger.debug("Init WangYue Project")
    val tasks = MongoDBManager.collection<RobotTask>().find(RobotTask::state lt RobotTaskState.Success).toList()
    tasks.forEach { if (it.def.contains("transfer") && !unfinishedTasks.contains(it.id)) unfinishedTasks[it.id] = it }
    executor.scheduleAtFixedRate(this::listenPlc, 5, 3, TimeUnit.SECONDS)
    helper.connect()
  }

  private fun listenPlc() {
    synchronized(helper) {
      if (flag) return
      flag = true
      try {
//        val vehicleSelector = getInjector()?.getInstance(IsAvailableForAnyOrder::class.java) ?: throw BusinessError("get Injector error")
//        val vs = VehicleService.listVehicles().filter { v -> vehicleSelector.test(v) }
        if (unfinishedTasks.size < 4) {
          val canSend = helper.read02DiscreteInputs(CUSTOM_CONFIG.listenAddr, 1, 1, "读取创建任务信号")?.getByte(0)?.toInt()
          if (canSend == 1) createTask(4 - unfinishedTasks.size)
        }
      } catch (e: Exception) {
        logger.error(e.message)
      } finally {
        flag = false
      }
    }
  }

  private fun createTask(num: Int) {
    val def = getRobotTaskDef("transfer") ?: throw BusinessError("No such task def 'transfer'")
    val task = buildTaskInstanceByDef(def)
    for (index in 1..num) RobotTaskService.saveNewRobotTask(task)
  }

  fun onRobotTaskCreated(task: RobotTask) {
    if (task.def.contains("transfer")) unfinishedTasks[task.id] = task
  }

  fun onRobotTaskUpdated(task: RobotTask) {
    if (task.def.contains("transfer")) unfinishedTasks[task.id] = task
  }

  fun onRobotTaskFinished(task: RobotTask) {
    try {
      if (!task.def.contains("transfer")) return
      unfinishedTasks.remove(task.id)
      if (task.state > RobotTaskState.Success) {
        when {
          task.transports[10].state == RobotTransportState.Success && task.transports[12].state < RobotTransportState.Sent ->
            if (CUSTOM_CONFIG.functionCode == "4x") helper.write10MultipleRegisters(CUSTOM_CONFIG.onPositionAddrD, 2, byteArrayOf(0, 0, 0, 0), 1, "任务异常导致的D复位")
            else helper.write0FMultipleCoils(CUSTOM_CONFIG.onPositionAddrD, 2, byteArrayOf(0), 1, "任务异常导致的D复位")

          task.transports[6].state == RobotTransportState.Success && task.transports[8].state < RobotTransportState.Sent ->
            if (CUSTOM_CONFIG.functionCode == "4x") helper.write10MultipleRegisters(CUSTOM_CONFIG.onPositionAddrC, 2, byteArrayOf(0, 0, 0, 0), 1, "任务异常导致的C复位")
            else helper.write0FMultipleCoils(CUSTOM_CONFIG.onPositionAddrC, 2, byteArrayOf(0), 1, "任务异常导致的C复位")

          task.transports[4].state == RobotTransportState.Success && task.transports[6].state < RobotTransportState.Sent ->
            if (CUSTOM_CONFIG.functionCode == "4x") helper.write10MultipleRegisters(CUSTOM_CONFIG.onPositionAddrB, 2, byteArrayOf(0, 0, 0, 0), 1, "任务异常导致的B复位")
            else helper.write0FMultipleCoils(CUSTOM_CONFIG.onPositionAddrB, 2, byteArrayOf(0), 1, "任务异常导致的B复位")

          task.transports[0].state == RobotTransportState.Success && task.transports[2].state < RobotTransportState.Sent ->
            if (CUSTOM_CONFIG.functionCode == "4x") helper.write10MultipleRegisters(CUSTOM_CONFIG.onPositionAddrA, 2, byteArrayOf(0, 0, 0, 0), 1, "任务异常导致的A复位")
            else helper.write0FMultipleCoils(CUSTOM_CONFIG.onPositionAddrA, 2, byteArrayOf(0), 1, "任务异常导致的A复位")
        }
      }
    } catch (e: Exception) {
      logger.error("reset task error", e)
    }
  }
}
