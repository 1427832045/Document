package com.seer.srd.lpsU.ur

import com.seer.srd.lpsU.CUSTOM_CONFIG
import com.seer.srd.lpsU.ProductLineService
import com.seer.srd.lpsU.Services
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.scheduler.ThreadFactoryHelper.buildNamedThreadFactory
import io.netty.buffer.ByteBufUtil
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.Exception
import kotlin.concurrent.read
import kotlin.concurrent.write

object UrListener {
  private val executor = Executors.newCachedThreadPool(buildNamedThreadFactory("srd-ur"))
  private val logger = LoggerFactory.getLogger("com.seer.srd.lpsU.ur")

  private val urExecutor = Executors.newScheduledThreadPool(1)

  val urModBusMap: MutableMap<String, Int> = ConcurrentHashMap()
  val srdModBusMap: MutableMap<String, Int> = ConcurrentHashMap()

  @Volatile
  private var checking = false

  @Volatile
  var urError = false

  val lock = Object()

  val readWriteLock = ReentrantReadWriteLock()

  @Volatile
  var writeCount = 0

  @Volatile
  var flag = true

  fun init() {
    logger.debug("监听 ur...")
    urExecutor.scheduleAtFixedRate(::listenUr, 0, 200, TimeUnit.MILLISECONDS)
  }

  private fun listenUr() {
    synchronized(lock) {
      if (checking) return
      checking = true
      try {
        while (writeCount > 0) {
          val driveReset = srdModBusMap["driveReset"]?.toByte()
          val clipNo = srdModBusMap["clipNo"]?.toByte()
          val agvBufferNo = srdModBusMap["agvBufferNo"]?.toByte()
          val path = srdModBusMap["path"]?.toByte()
          val side = srdModBusMap["side"]?.toByte()
          val ocr = srdModBusMap["ocr"]?.toByte()
          val bufferClipNo = srdModBusMap["bufferClipNo"]?.toByte()
          val checkOK = srdModBusMap["checkOK"]?.toByte()
          val bufferOK = srdModBusMap["bufferOK"]?.toByte()

          if (driveReset is Byte && clipNo is Byte && agvBufferNo is Byte && path is Byte && side is Byte && ocr is Byte &&
              bufferClipNo is Byte && checkOK is Byte && bufferOK is Byte) {

            val bArray = byteArrayOf(0, driveReset, 0, clipNo, 0, agvBufferNo, 0, path, 0, side, 0, ocr, 0, bufferClipNo, 0, checkOK, 0, bufferOK)
            UrModbusService.helper.write10MultipleRegisters(169, 9, bArray, 1, "set,box_num,agv_buffer_box,operation,park_point,ocr,dev_box_num,checkOK,devOK")

            logger.debug("UR交互信号更新: $writeCount")
            writeCount--

            Thread.sleep(200)
          }

        }
        val arr = ByteBufUtil.hexDump(UrModbusService.helper.read03HoldingRegisters(159, 20, 1, "read all UR data"))
        if (arr.isNotEmpty()) {
          val data = arr.map { Integer.valueOf(it.toString(), 16) }
          // UR可写入的数据
          urModBusMap["reset"] = data[3]
          urModBusMap["takeComplete"] = data[7]
          urModBusMap["putComplete"] = data[11]
          urModBusMap["getORC"] = data[15]
          urModBusMap["timeout"] = data[19]
          urModBusMap["urLeaveBuffer"] = data[23]

          // SRD可写入的数据
          srdModBusMap["driveReset"] = data[43]
          srdModBusMap["clipNo"] = data[47]
          srdModBusMap["agvBufferNo"] = data[51]
          srdModBusMap["path"] = data[55]
          srdModBusMap["side"] = data[59]
          srdModBusMap["ocr"] = data[63]
          srdModBusMap["bufferClipNo"] = data[67]
          srdModBusMap["checkOK"] = data[71]
          srdModBusMap["bufferOK"] = data[75]

          if (srdModBusMap["driveReset"] == null) {
            logger.debug("init srd modBus data map")
            srdModBusMap["driveReset"] = data[43]
            srdModBusMap["clipNo"] = data[47]
            srdModBusMap["agvBufferNo"] = data[51]
            srdModBusMap["path"] = data[55]
            srdModBusMap["side"] = data[59]
            srdModBusMap["ocr"] = data[63]
            srdModBusMap["bufferClipNo"] = data[67]
            srdModBusMap["checkOK"] = data[71]
            srdModBusMap["bufferOK"] = data[75]
          }
//          if (srdModBusMap["clipNo"] != null) srdModBusMap["clipNo"] = data[47]
//          if (srdModBusMap["bufferNo"] != null) srdModBusMap["bufferNo"] = data[51]
//          if (srdModBusMap["path"] != null) srdModBusMap["path"] = data[55]
//          if (srdModBusMap["side"] != null) srdModBusMap["side"] = data[59]
//          if (srdModBusMap["ocr"] != null) srdModBusMap["ocr"] = data[63]
//          if (srdModBusMap["bufferClipNo"] != null) srdModBusMap["bufferClipNo"] = data[67]
//          if (srdModBusMap["checkOK"] != null) srdModBusMap["checkOK"] = data[71]
//          if (srdModBusMap["bufferOK"] != null) srdModBusMap["bufferOK"] = data[75]

          when {
            data[3] == 1 -> {
              UrModbusService.helper.write10MultipleRegisters(169, 9, ByteArray(18){ 0 }, 1, "SRD->UR信号复位")
              logger.debug("SRD->UR信号复位成功")
              Thread.sleep(200)
            }
            data[3] == 3 -> logger.debug("ur 启动..")
            else -> {}
          }
          urError = false
        }
      } catch (e: Exception) {
        logger.error("get UR data error, ${e.message}")
        urError = true
//        if (readWriteLock.isWriteLocked) readWriteLock.writeLock().unlock()
      } finally {
        checking = false
      }
    }
  }

  fun listen() {
    executor.submit {
      while (true) {
        if (!flag) continue
        Thread.sleep(2000)
        try {
          val reset = UrModbusService.helper.read03HoldingRegisters(159, 1, 1, "ur复位信号")?.getShort(0)?.toInt()
          if (reset == 1) {
            logger.debug("UR复位成功")

            UrModbusService.helper.write06SingleRegister(164, 0, 1, "料车弹匣复位信号")
            logger.debug("弹匣信号复位成功")

            UrModbusService.helper.write06SingleRegister(165, 0, 1, "AGV缓存复位信号")
            logger.debug("AGV缓存信号成功")

            UrModbusService.helper.write06SingleRegister(170, 0, 1, "机台弹匣复位信号")
            logger.debug("机台弹匣信号复位成功")

            UrModbusService.helper.write06SingleRegister(167, 0, 1, "动作复位信号")
            logger.debug("动作信号复位成功")

            UrModbusService.helper.write06SingleRegister(168, 0, 1, "料车位置复位信号")
            logger.debug("料车位置信号复位成功")

            UrModbusService.helper.write06SingleRegister(169, 0, 1, "OCR识别复位信号")
            logger.debug("OCR识别信号复位成功")

            UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK复位信号")
            logger.debug("checkOK复位成功")

            UrModbusService.helper.write06SingleRegister(163, 0, 1, "驱使复位信号")
            logger.debug("驱使信号复位成功")
          }
          if (reset == 3) {
            logger.debug("ur 启动..")
//          UrModbusService.helper.write06SingleRegister(163, 1, 1, "ur启动回复set=1")
          }
        } catch (e:Exception) {
          logger.error("connect ur modbus sever error", e)
          try {
            UrModbusService.helper.connect()
          } catch (e: Exception) {
            logger.error("reconnect ur modbus sever error...")
          }
        }
      }
    }
  }

  fun onRobotTaskFinished(task: RobotTask) {
    logger.debug("完成任务后的检查")
//    val existed = MongoDBManager.collection<RobotTask>().findOne(RobotTask::state lt RobotTaskState.Success)
    Thread.sleep(1000)
    if (task.state > RobotTaskState.Success) {
      if (task.def !in listOf("recognize", "DownTaskOneCar", "DownTaskTwoCar", "DownTask", "UpTask")) logger.warn("未知的任务类型：${task.def}")
      logger.debug("任务${task.id} 状态：${task.state}, 开始信号复位...")

      synchronized(UrListener.lock) {
        srdModBusMap["clipNo"] = 0
        srdModBusMap["agvBufferNo"] = 0
        srdModBusMap["path"] = 0
        srdModBusMap["side"] = 0
        srdModBusMap["ocr"] = 0
        srdModBusMap["bufferClipNo"] = 0
        srdModBusMap["checkOK"] = 0
        srdModBusMap["bufferOK"] = 0
        srdModBusMap["driveReset"] = 0
        writeCount++
      }

      val line11 = ProductLineService.getLineById("line11")
      val line12 = ProductLineService.getLineById("line12")

      if (task.def.contains("UpTask")) {
        Services.operating = false
        val type = task.persistedVariables["lineId"] as String
        if (type.contains("11")) {
          line11.upModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.checkAddr, 0, 1, "task finished:check复位")
          Thread.sleep(200)
          line11.upModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.urUpOperationAddr, 0, 1, "task finished:占用机台复位")
          Thread.sleep(200)
          line11.downModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.standByAddr, 0, 1, "up task finished:离开line11机台")
        }
        if (type.contains("12")) {
          line12.upModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.checkAddr, 0, 1, "task finished:check复位")
          Thread.sleep(200)
          line12.upModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.urUpOperationAddr, 0, 1, "task finished:占用机台复位")
          Thread.sleep(200)
          line12.downModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.standByAddr, 0, 1, "up task finished:离开line12机台")
        }
      }

      if (task.def.contains("DownTask")) {
        val type = task.persistedVariables["lineId"] as String
        if (type.contains("11")) {
          line11.downModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.checkAddr, 0, 1, "task finished:check复位")
          Thread.sleep(200)
          line11.downModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.urDownOperationAddr, 0, 1, "task finished:占用机台复位")
          Thread.sleep(200)
          line11.downModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.standByAddr, 0, 1, "down task finished:离开line11机台")
        }
        if (type.contains("12")) {
          line12.downModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.checkAddr, 0, 1, "task finished:check复位")
          Thread.sleep(200)
          line12.downModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.urDownOperationAddr, 0, 1, "task finished:占用机台复位")
          Thread.sleep(200)
          line12.downModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.standByAddr, 0, 1, "down task finished:离开line12机台")
        }
      }

    }
  }
}

