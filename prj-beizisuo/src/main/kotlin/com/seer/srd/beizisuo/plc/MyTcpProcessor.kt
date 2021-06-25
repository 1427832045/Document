package com.seer.srd.beizisuo.plc

import com.seer.srd.BusinessError
import com.seer.srd.beizisuo.CUSTOM_CONFIG
import com.seer.srd.beizisuo.plc.MyTcpServer.socketMap
import com.seer.srd.db.MongoDBManager
import com.seer.srd.io.tcp.ByteArrayBuffer
import com.seer.srd.robottask.*
import org.litote.kmongo.eq
import org.litote.kmongo.lt
import org.litote.kmongo.setValue
import org.slf4j.LoggerFactory
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

val tcpPkgHead = byteArrayOf(0x8, 0x8)

class MyTcpProcessor(var socket: Socket) {
  private val logger = LoggerFactory.getLogger(MyTcpProcessor::class.java)
  private val pkgExtractor = MyPkgExtractor(tcpPkgHead, this::parsePkgLen, this::onPkg)
  private val inputStreamToPkg = MyInputStreamToPkg(socket.getInputStream(), 128, this.pkgExtractor, this::onError)
  private val executor = Executors.newCachedThreadPool()

  init {
    inputStreamToPkg.start()
    socketMap[socket.remoteSocketAddress.toString()] = this
    logger.debug("init tcp server, remoteSocketAddress=${socket.remoteSocketAddress}")
  }

  private fun parsePkgLen(buffer: ByteArrayBuffer): Int {
    if (buffer.validEndIndex - buffer.validStartIndex < tcpPkgHead.size) return -1
    val byteBuffer = ByteBuffer.wrap(buffer.buffer, buffer.validStartIndex + tcpPkgHead.size, 1)
    byteBuffer.order(ByteOrder.BIG_ENDIAN)
    val len = byteBuffer.get().toInt() + tcpPkgHead.size + 1 // 要包含头
    buffer.validEndIndex = buffer.validStartIndex + len
    return len
  }

  fun dispose() {
    inputStreamToPkg.stop()
    socket.close()
  }

  private fun onPkg(buffer: ByteArrayBuffer, pkgLen: Int) {
    val buf = ByteBuffer.wrap(
        buffer.buffer,
        buffer.validStartIndex,
        pkgLen
    ).order(ByteOrder.BIG_ENDIAN)
    val head1 = buf.get().toInt()
    val head2 = buf.get().toInt()
    val dataLen = buf.get().toInt()
    val taskType = buf.get().toInt()
    val taskCheck = buf.get().toInt()
    val loadMachineStatus = buf.get().toInt()
    val unloadMachineStatus = buf.get().toInt()
    val mode = buf.get().toInt()
    logger.debug("got data, head=0x$head1 0x$head2, valid byte length=$dataLen, task type=$taskType, creat task or not=$taskCheck," +
        " load machine status=$loadMachineStatus, unload machine status=$unloadMachineStatus, mode=$mode")
    if (dataLen < CUSTOM_CONFIG.dataFromLen) {
      logger.warn("date length is not enough, skip ...")
      return
    }
    if (taskType == 0) {
      logger.debug("taskType = 0, invalid data, skip ...")
      return
    }

    if (CUSTOM_CONFIG.skipSameTaskType && taskCheck == 1) {
      val unfinishedTasks = MongoDBManager.collection<RobotTask>().find(RobotTask::state lt RobotTaskState.Success).toList()
      for (task in unfinishedTasks) {
        if (task.persistedVariables["taskType"] == taskType) {
          logger.debug("Exists task type $taskType in system, task id=${task.id}, skip to create new task...")
          return
        }
      }
//      unfinishedTasks.forEach {
//        if (it.persistedVariables["taskType"] == taskType) {
//          logger.debug("Exists task type $taskType in system, task id=${it.id}, skip to create new task...")
//          return
//        }
//      }
    }

    val msg: String
    val from: String
    val to: String
    val priority: Int
    when (taskType) {
      1 -> {
        from = "A"
        to = "B"
        msg = "try to create task from A to B"
        priority = 40
      }
      2 -> {
        from = "B"
        to = "C"
        msg = "try to create task from B to C"
        priority = 30
      }
      3 -> {
        from = "A"
        to = "D2"
        msg = "try to create task from A to D2"
        priority = 30
      }
      4 -> {
        from = "D2"
        to = "E"
        msg = "try to create task from D2 to E"
        priority = 10
      }
      5 -> {
        from = "C"
        to = "D1"
        msg = "try to create task from C to D1"
        priority = 0
      }
      else -> {
        logger.debug("invalid type $taskCheck")
        return
      }
    }

//    val machineFromState = CUSTOM_CONFIG.crateTaskLoadMachineStatus
//    val machineToState = CUSTOM_CONFIG.crateTaskUnloadMachineStatus
    if (taskCheck == CUSTOM_CONFIG.createTaskByStatus){
      logger.debug(msg)
      // 创建任务信号
//      if (loadMachineStatus == machineFromState && unloadMachineStatus == machineToState) {     //工位机器人空闲的时候才生成任务
      val def = getRobotTaskDef(CUSTOM_CONFIG.taskDefName)
          ?: throw BusinessError("unknown def: ${CUSTOM_CONFIG.taskDefName}")
      val task = buildTaskInstanceByDef(def)
      task.priority = priority
      task.transports[0].stages[1].location = from
      task.transports[1].stages[2].location = from
      task.transports[1].stages[3].location = to
      task.transports[2].stages[2].location = to
      task.transports[3].stages[0].location = to
      task.transports[3].stages[1].location = to
      task.persistedVariables["from"] = from
      task.persistedVariables["loaded"] = false
      task.persistedVariables["to"] = to
      task.persistedVariables["unloaded"] = false
      task.persistedVariables["taskType"] = taskType
      task.persistedVariables["remoteAddr"] = socket.remoteSocketAddress.toString()
      RobotTaskService.saveNewRobotTask(task)
//      }
    } else {
      when {
        unloadMachineStatus == 1 -> {
          logger.debug("AGV arrived to station: $to")
          executor.submit {
            val unfinishedTasks = MongoDBManager.collection<RobotTask>().find(RobotTask::state lt RobotTaskState.Success).toList()
            val tasks = unfinishedTasks.filter { it.persistedVariables["taskType"] == taskType }
            tasks.forEach { task ->
              if (task.transports[1].state == RobotTransportState.Success && task.transports[2].state < RobotTransportState.Sent) {
                logger.debug("task type = $taskType, task ${task.id} arrived final station '$to' and already unloaded, task is finished")
                val pv = task.persistedVariables
                pv["unloaded"] = true
                MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq task.id, setValue(RobotTask::persistedVariables, pv))
                return@forEach
              }
            }
          }
        }
        loadMachineStatus == 1 -> {
          logger.debug("AGV arrived from station: $from")
          executor.submit {
            val unfinishedTasks = MongoDBManager.collection<RobotTask>().find(RobotTask::state lt RobotTaskState.Success).toList()
            val tasks = unfinishedTasks.filter { it.persistedVariables["taskType"] == taskType }
            tasks.forEach { task ->
              if (task.transports[0].state == RobotTransportState.Success && task.transports[1].state < RobotTransportState.Sent) {
                logger.debug("task type = $taskType, task ${task.id} arrived first station $from and already loaded, try to go to terminal station $to")
                val pv = task.persistedVariables
                pv["loaded"] = true
                MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq task.id, setValue(RobotTask::persistedVariables, pv))
                return@forEach
              }
            }
          }
        }
        else -> logger.debug("invalid data")
      }
    }
  }

  private fun onError(e: Exception) {
    logger.error("my tcp processor error", e)
  }
}