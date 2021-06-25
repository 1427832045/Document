package com.seer.srd.lps3

import com.seer.srd.BusinessError
import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class LiftManager(val liftConfig: LiftConfig) {

  private val logger = LoggerFactory.getLogger(LiftManager::class.java)

  @Volatile
  private var model: LiftModel = LiftModel(liftConfig.name)

  @Volatile
  private var enabled = false

  private val liftHelper: ModbusTcpMasterHelper = ModbusTcpMasterHelper(liftConfig.host, liftConfig.port)

  private val timer = Executors.newScheduledThreadPool(1)!!

  private val future: ScheduledFuture<*>

  private var printRequestCount = 1

  init {
    liftHelper.connect()
    future = timer.scheduleAtFixedRate(
        this::requestStatus,
        1000,
        CUSTOM_CONFIG.liftStatusPollingPeriod,
        TimeUnit.MILLISECONDS
    )
  }

  fun dispose() {
    liftHelper.disconnect()
  }

  fun getLiftModel(): LiftModel {
    return model
  }

  fun setOccupied(occupied: Boolean, remark: String) {
    logger.info("setOccupied $occupied lift=${liftConfig.name}. $remark")
    model = model.copy(isOccupy = occupied, destFloor = if (!occupied) null else model.destFloor)

    // 关门由电梯PLC控制
    inPlace(occupied)
  }

  fun call(floor: String, remark: String) {
    enabled = true
    if (model.isOccupy) {
      logger.error("call but occupied, lift=${liftConfig.name}")
      throw BusinessError("CallButOccupied ${liftConfig.name}")
    }

    logger.info("Call to floor $floor lift=${liftConfig.name}. $remark")
    model = model.copy(destFloor = floor)

    to(floor, remark)
  }

  fun go(floor: String, remark: String) {
    enabled = true
    if (!model.isOccupy) {
      logger.error("go but not occupied, lift=${liftConfig.name}")
      throw BusinessError("GoButNotOccupied ${liftConfig.name}")
    }

    logger.info("go to floor $floor lift=${liftConfig.name}. $remark")
    model = model.copy(destFloor = floor)

//    to(floor, remark)
  }

  @Synchronized
  private fun requestStatus() {
    try {
      if (!enabled) return
      var open1: Int? = 0
      var open2: Int? = 0
      if (liftConfig.readFuncCode == "02") {
        open1 = liftHelper.read02DiscreteInputs(liftConfig.open1, 1, 1, "${liftConfig.name} open1")?.getByte(0)?.toInt()
        open2 = liftHelper.read02DiscreteInputs(liftConfig.open2, 1, 1, "${liftConfig.name} open2")?.getByte(0)?.toInt()
      }
      if (liftConfig.readFuncCode == "01") {
        open1 = liftHelper.read01Coils(liftConfig.open1, 1, 1, "${liftConfig.name} open1")?.getByte(0)?.toInt()
        open2 = liftHelper.read01Coils(liftConfig.open2, 1, 1, "${liftConfig.name} open2")?.getByte(0)?.toInt()
      }

      if (printRequestCount++%20 == 0) {
        printRequestCount == 0
        logger.debug("request lift Status funcCode=${liftConfig.readFuncCode}, DI0=$open1, DI1=$open2")
      }
      if (open1 is Int && open2 is Int) {
        var currentFloor: String? = ""
        var status: LiftStatus? = LiftStatus.CLOSE
        when {
          open1 > 0 -> {
            currentFloor = "1"
            status = LiftStatus.OPEN
          }
          open2 > 0 -> {
            currentFloor = "2"
            status = LiftStatus.OPEN
          }
          else -> {
            if (open1 != 0 || open2 != 0)
              logger.debug("invalid value: open1=$open1, open2=$open2")
          }
        }
        if (currentFloor != model.currentFloor || status != model.status) {
          val newModel = model.copy(
              currentFloor = currentFloor,
              status = status
          )
          logger.info("lift=${liftConfig.name} changed from $model to $newModel")
          model = newModel
        }
      } else {
        logger.error("lift=${liftConfig.name} modbus value read error open1=$open1, open2=$open2")
      }
    } catch (e: Exception) {
      logger.error("request lift error", e)
    }
  }

  @Synchronized
  private fun to(floor: String, remark: String) {
    when (floor) {
      "1" -> {
        if (liftConfig.writeFuncCode == "05")
          liftHelper.write05SingleCoil(liftConfig.floor1, true, 1, "1楼呼叫电梯 $remark")
        if (liftConfig.writeFuncCode == "06")
          liftHelper.write06SingleRegister(liftConfig.floor1, 1, 1, "1楼呼叫电梯 $remark")
      }
      "2" -> {
        if (liftConfig.writeFuncCode == "05")
          liftHelper.write05SingleCoil(liftConfig.floor2, true, 1, "2楼呼叫电梯 $remark")
        if (liftConfig.writeFuncCode == "06")
          liftHelper.write06SingleRegister(liftConfig.floor2, 1, 1, "2楼呼叫电梯 $remark")
      }
      else -> {
        logger.error("lift=${liftConfig.name} unavailable floor $floor")
        throw BusinessError("${liftConfig.name} unavailable floor $floor")
      }
    }
  }

  @Synchronized
  private fun inPlace(occupied: Boolean) {
    if (occupied) {
      if (liftConfig.writeFuncCode == "05") {
        liftHelper.write05SingleCoil(liftConfig.inPlace, true, 1, "${liftConfig.name} occupied")
        liftHelper.write05SingleCoil(liftConfig.floor1, false, 1, "${liftConfig.name} OUT1 OFF")
        liftHelper.write05SingleCoil(liftConfig.floor2, false, 1, "${liftConfig.name} OUT3 OFF")
      }
      if (liftConfig.writeFuncCode == "06") {
        liftHelper.write06SingleRegister(liftConfig.inPlace, 1, 1, "${liftConfig.name} occupied")
        liftHelper.write06SingleRegister(liftConfig.floor1, 0, 1, "${liftConfig.name} OUT1 OFF")
        liftHelper.write06SingleRegister(liftConfig.floor2, 0, 1, "${liftConfig.name} OUT3 OFF")
      }
    }
    else {
      if (liftConfig.writeFuncCode == "05") {
        liftHelper.write05SingleCoil(liftConfig.inPlace, false, 1, "${liftConfig.name} released")
        liftHelper.write05SingleCoil(liftConfig.floor1, false, 1, "${liftConfig.name} OUT1 OFF")
        liftHelper.write05SingleCoil(liftConfig.floor2, false, 1, "${liftConfig.name} OUT3 OFF")
        enabled = false
      }
      if (liftConfig.writeFuncCode == "06") {
        liftHelper.write06SingleRegister(liftConfig.inPlace, 0, 1, "${liftConfig.name} released")
        liftHelper.write06SingleRegister(liftConfig.floor1, 0, 1, "${liftConfig.name} OUT1 OFF")
        liftHelper.write06SingleRegister(liftConfig.floor2, 0, 1, "${liftConfig.name} OUT3 OFF")
        enabled = false
      }
    }
  }
}

data class LiftModel(
    val name: String,
    val currentFloor: String? = null,
    val destFloor: String? = null,
    val isOccupy: Boolean = false,
    val isEmergency: Boolean = false,
    val moveStatus: LiftMoveStatus? = null,
    val status: LiftStatus? = LiftStatus.CLOSE // todo rename to doorStatus
)

enum class LiftStatus {
  OPEN, OPENING, CLOSE, CLOSING, ERROR
}

enum class LiftMoveStatus {
  Error, Hold, Down, Up
}
