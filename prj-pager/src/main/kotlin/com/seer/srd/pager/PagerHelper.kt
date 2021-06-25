package com.seer.srd.pager

import com.seer.srd.BusinessError
import com.seer.srd.SystemError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.pager.PagerTcpServer.pagerToSocketMap
import com.seer.srd.robottask.*
import com.seer.srd.route.service.VehicleService
import com.seer.srd.vehicle.Vehicle
import com.seer.srd.vehicle.driver.io.tcp.pkgLenWidth
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.litote.kmongo.lt
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object PagerHelper {

  val lights: Map<String, String> = emptyMap()
  private val callerTimer = Executors.newScheduledThreadPool(1)

  private val logger = LoggerFactory.getLogger(PagerHelper::class.java)
  lateinit var futureTask: ScheduledFuture<*>

  private var calledChecking = false

  // key是集中器ID和节点ID
  val calledDataMap: MutableMap<String, Download> = mutableMapOf()

//  fun getSumByByteList():Int {
//    val a = String.format("%02X", 2f.toByte())
//    val b = ubyteArrayOf(12u, 23u)
//    return 0
//  }

  fun jointByteToHexString(a: ByteArray) {

  }

  fun init() {
    logger.debug("schedule caller task at fixed rate  ...")
    calledDataMap.clear()
    futureTask = callerTimer.scheduleAtFixedRate(this::called, 1, 2, TimeUnit.SECONDS)
  }

  private fun called() {
    synchronized(callerTimer) {
      if (calledChecking) return
      calledChecking = true
      try {

        calledDataMap.forEach {
          val concentratorId = it.key.substring(0, 8)
          val nodeId = it.key.substring(8)
          val data = it.value
//          // todo 检查这个命令是响应呼叫模式的
//          if (data.data.fromAddr != "2") {
//            logger.debug("不是响应呼叫模式，fromAddr: ${data.data.fromAddr}")
//            return
//          }
          val server = pagerToSocketMap[concentratorId] ?: throw SystemError("集中器【$concentratorId】与SRD未建立连接")

          val taskId = getTaskIdByNodeId(it.key, "呼叫")
          if (taskId.isNullOrBlank()) return

          // 呼叫任务开始执行时，把灯置为绿色闪烁
          val existed = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq taskId) ?: return

          val transport = existed.transports[1]
          val location = transport.stages[0].location
//          val processRobot = transport.processingRobot

          val routeOrder = transport.routeOrderName
          var vehicle = ""
          VehicleService.listVehicles().forEach { v ->
            if (v.transportOrder == routeOrder) vehicle = v.name
          }
          val expectedLocation = CUSTOM_CONFIG.concentrators.findLast { c -> c.id == concentratorId }?.nodes?.findLast { n -> n.id == nodeId }?.location
//          if (processRobot.isNullOrBlank() || location != expectedLocation) return
          if (vehicle.isBlank() || location != expectedLocation) return

          val cId = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(Integer.parseInt(concentratorId, 16)).array()
          val nId = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(Integer.parseInt(nodeId, 16)).array()
//          val len = byteArrayOf(Integer.parseInt(data.dataLength, 16).toByte())
          val len = byteArrayOf(0x0c)
          val head = byteArrayOf(Integer.parseInt(data.data.frameHead, 16).toByte())
          val frameId = byteArrayOf(Integer.parseInt(data.data.frameId, 16).toByte())
          val functionCode = byteArrayOf(Integer.parseInt(data.data.functionCode, 16).toByte())
//          val fromAddr = byteArrayOf(Integer.parseInt(data.data.fromAddr, 16).toByte())
//          val registerNum = byteArrayOf(Integer.parseInt(data.data.registerNum, 16).toByte())
//          val registerValue = byteArrayOf(Integer.parseInt(data.data.registerValue, 16).toByte())
//          val check = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(Integer.parseInt(data.data.check, 16).toShort()).array()
          val fromAddr = byteArrayOf(0x00)
          val registerNum = byteArrayOf(0x04)
          val registerValue = byteArrayOf(0x00, 0x00, 0x0a, 0x00)
          val c = Integer.parseInt(data.data.frameId, 16) + 1 + 0 + 4 + 10
          val check = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(c.toShort()).array()
          val tail = byteArrayOf(Integer.parseInt(data.data.frameTail, 16).toByte())

          server.write(cId + nId + len + head + frameId + functionCode + fromAddr + registerNum + registerValue + check + tail, true)
          logger.debug("将呼叫灯置为绿色闪烁, 节点$nodeId")

          calledDataMap[concentratorId + nodeId] = calledDataMap[concentratorId + nodeId]!!.copy(taskId = null)
//         todo calledDataMap[concentratorId + nodeId]?.copy(taskId = null)  放到组件中
        }

      } catch (e: Exception) {
        logger.debug("called error", e)
      } finally {
        calledChecking = false
      }
    }
  }

  @Synchronized
  fun createTask(id: String): String? {

    // 检查是否有该节点呼叫任务
    val concentratorId = id.substring(0, 8)
    val nodeId = id.substring(8)
    val concentrator = CUSTOM_CONFIG.concentrators.findLast { it.id == concentratorId } ?: throw BusinessError("no such concentrator id: $concentratorId")
    val node = concentrator.nodes.findLast { it.id == nodeId } ?: throw BusinessError("no such node id: $nodeId")

    val taskId = getTaskIdByNodeId(id, "呼叫")
    if (!taskId.isNullOrBlank()) return null

    val def = getRobotTaskDef("caller") ?: throw BusinessError("NoSuchTaskDef: 'caller'")
    val task = buildTaskInstanceByDef(def)

    task.persistedVariables["fromSite"] = node.location
    RobotTaskService.saveNewRobotTask(task)
    return task.id
  }

  private fun getTaskIdByNodeId(id: String, mode: String): String? {
    if (mode == "呼叫") {
      // 01 03 E3 23 C4 89 A5 5C B8 23 00 00 00 00 00 00 00 00 3a 89 68 36 00 00 03 00 09 aa 00 01 04 01 0a 00 0f 55
      val concentratorId = id.substring(0, 8)
      val nodeId = id.substring(8)
      return calledDataMap[concentratorId + nodeId]?.taskId
    }
    return null
  }

  fun dispose() {
    futureTask.cancel(true)
    callerTimer.shutdown()
  }
}

data class Upload(
    // 版本号：1字节
    val version: String = CUSTOM_CONFIG.version,
    // 命令号：1字节
    val command: String = CUSTOM_CONFIG.command,
//    // 集中器ID：4字节
//    val concentratorId: String,
    // 节点ID：4字节
    val nodeId: String,
    // 短ID：2字节
    val shortId: String,
    // 通道：1字节
    val channel: String,
    // SNR：1字节
    val snr: String,
    // 1字节
    val rssi0: String,
    // 1字节
    val rssi1: String,
    // 1字节
    val nc1: String,
    // 1字节
    val nc2: String,
    // 时间戳：4字节
    val timeStamp: String,
    // 在线：1字节，0x01离线，0x00在线
    val online: String,
    // 入网情况：1字节
    val inNet: String,
    // 有效数据长度：length = data.length
    val dataLength: String,
    // 有效数据
    val data: PagerData
)

data class Download(
    // 有效数据长度：length = data.length
    val dataLength: String,
    // 有效数据
    val data: PagerData,
    // 任务
    val taskId: String?
)

data class PagerData(
    // 帧头：1字节
    val frameHead: String = CUSTOM_CONFIG.frameHead,
    // 帧ID：1字节
    val frameId: String,
    // 功能码：1字节
    val functionCode: String,
    // 起始地址：1字节
    val fromAddr: String,
    // 寄存器数量：1字节
    val registerNum: String,
    // 写入寄存器的值(字节数由寄存器数量决定，写入时有效)
    val registerValue: String?,
    // 校验和，2字节
    val check: String,
    // 帧尾：1字节
    val frameTail: String = CUSTOM_CONFIG.frameTail
)