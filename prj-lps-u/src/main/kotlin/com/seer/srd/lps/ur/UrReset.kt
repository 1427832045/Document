package com.seer.srd.lps.ur

import com.seer.srd.lps.CUSTOM_CONFIG
import com.seer.srd.lps.ProductLineService
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.scheduler.ThreadFactoryHelper.buildNamedThreadFactory
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.util.concurrent.Executors

object UrReset {
  private val executor = Executors.newCachedThreadPool(buildNamedThreadFactory("srd-ur"))
  private val logger = LoggerFactory.getLogger("com.seer.srd.lps.ur")
  @Volatile
  var flag = true
  init {
    logger.debug("监听 ur...")
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
    if (task.state >= RobotTaskState.Success) {
      if (task.def !in listOf("recognize", "DownTask", "UpTask")) logger.warn("未知的任务类型：${task.def}")
      logger.debug("任务${task.id}状态：${task.state}, 开始信号复位...")

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

      val line11 = ProductLineService.getLineById("line11")
      val line12 = ProductLineService.getLineById("line12")

      if (task.def.contains("UpTask")) {
        val type = task.persistedVariables["lineId"] as String
        if (type.contains("11")) {
          line11.upModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.checkAddr, 0, 1, "task finished:check复位")
          line11.upModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.urUpOperationAddr, 0, 1, "task finished:占用机台复位")
          line11.downModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.standByAddr, 0, 1, "up task finished:离开line11机台")
        }
        if (type.contains("12")) {
          line12.upModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.checkAddr, 0, 1, "task finished:check复位")
          line12.upModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.urUpOperationAddr, 0, 1, "task finished:占用机台复位")
          line12.downModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.standByAddr, 0, 1, "up task finished:离开line12机台")
        }
      }

      if (task.def.contains("DownTask")) {
        val type = task.persistedVariables["lineId"] as String
        if (type.contains("11")) {
          line11.downModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.checkAddr, 0, 1, "task finished:check复位")
          line11.downModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.urDownOperationAddr, 0, 1, "task finished:占用机台复位")
          line11.downModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.standByAddr, 0, 1, "down task finished:离开line11机台")
        }
        if (type.contains("12")) {
          line12.downModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.checkAddr, 0, 1, "task finished:check复位")
          line12.downModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.urDownOperationAddr, 0, 1, "task finished:占用机台复位")
          line12.downModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.standByAddr, 0, 1, "down task finished:离开line12机台")
        }
      }

    }
  }
}