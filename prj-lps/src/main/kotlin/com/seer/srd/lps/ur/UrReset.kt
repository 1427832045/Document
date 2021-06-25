package com.seer.srd.lps.ur

import com.seer.srd.db.MongoDBManager
import com.seer.srd.lps.CUSTOM_CONFIG
import com.seer.srd.lps.ProductLineService
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.scheduler.ThreadFactoryHelper.buildNamedThreadFactory
import io.netty.buffer.ByteBufUtil
import org.litote.kmongo.`in`
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.litote.kmongo.lt
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

object UrReset {
  private val executor = Executors.newCachedThreadPool(buildNamedThreadFactory("srd-ur"))
  private val logger = LoggerFactory.getLogger("com.seer.srd.lps.ur")
  init {
    logger.debug("监听 ur...")
  }
  fun listen() {
    executor.submit {
      while (true) {
        val flag = UrModbusService.helper.read03HoldingRegisters(159, 1, 1, "ur复位信号")?.getShort(0)?.toInt()
        if (flag == 1) {
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
        if (flag == 3) {
          logger.debug("ur 启动..")
//          UrModbusService.helper.write06SingleRegister(163, 1, 1, "ur启动回复set=1")
        }
      }
    }
  }

  fun onRobotTaskFinished(task: RobotTask) {
    logger.debug("完成任务后的检查")
//    val existed = MongoDBManager.collection<RobotTask>().findOne(RobotTask::state lt RobotTaskState.Success)
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
      line11.upModbusHelper.write05SingleCoil(CUSTOM_CONFIG.modbusAddress.upA, false, 1, "lin11上料占用复位")
      line11.downModbusHelper.write05SingleCoil(CUSTOM_CONFIG.modbusAddress.downA, false, 1, "lin11下料占用复位")
      line12.upModbusHelper.write05SingleCoil(CUSTOM_CONFIG.modbusAddress.upA, false, 1, "lin12上料占用复位")
      line12.downModbusHelper.write05SingleCoil(CUSTOM_CONFIG.modbusAddress.downA, false, 1, "lin12下料占用复位")
    }
  }
}