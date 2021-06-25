package com.seer.srd.lps3

import com.seer.srd.BusinessError
import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import org.slf4j.LoggerFactory

object LiftService {

  private val logger = LoggerFactory.getLogger(LiftService::class.java)

  private val lifts: Map<String, LpsLift> = mapOf("lift1" to LpsLift("lift1"))
//  private val lifts: MutableMap<String, LpsLift> = mutableMapOf()

  fun init() {
    logger.debug("init lps3 lift ...")
  }

  fun getLiftByName(name: String): LpsLift {
    return lifts[name] ?: throw BusinessError("No such Lift Config $name")
  }

  fun dispose() {
    lifts.forEach {
      try {
        it.value.dispose()
      } catch (e: Exception) {
        logger.error("dispose lift ${it.key}", e)
      }
    }
  }

//  init {
//    CUSTOM_CONFIG.lifts.forEach {
//      lifts[it.key] = LpsLift(it.key)
//    }
//  }
}

class LpsLift(private val name: String) {

  private val logger = LoggerFactory.getLogger(LpsLift::class.java)

  @Volatile
  var isOccupied = false
  val liftModbusHelper: ModbusTcpMasterHelper

  init {
    val lift = CUSTOM_CONFIG.myLifts.findLast { it.name == name } ?: throw BusinessError("No such Lift Config $name")
    liftModbusHelper = ModbusTcpMasterHelper(lift.host, lift.port)
    liftModbusHelper.connect()
  }

  fun dispose() {
    liftModbusHelper.disconnect()
  }

  fun up() {
    val lift = CUSTOM_CONFIG.myLifts.findLast { it.name == name } ?: throw BusinessError("No such Lift Config $name")
    liftModbusHelper.write05SingleCoil(lift.floor2, true, 1, "上2楼")
  }

  fun down() {
    val lift = CUSTOM_CONFIG.myLifts.findLast { it.name == name } ?: throw BusinessError("No such Lift Config $name")
    liftModbusHelper.write05SingleCoil(lift.floor1, true, 1, "到1楼")
  }

  fun inPlace() {
    val lift = CUSTOM_CONFIG.myLifts.findLast { it.name == name } ?: throw BusinessError("No such Lift Config $name")
    liftModbusHelper.write05SingleCoil(lift.inPlace, true, 1, "AGV进电梯到位")
  }

  fun canPass(floor: Int, remark: String = ""): Boolean {
    val lift = CUSTOM_CONFIG.myLifts.findLast { it.name == name } ?: throw BusinessError("No such Lift Config $name")
    return when (floor) {
      1 -> {
        val pass = liftModbusHelper
            .read02DiscreteInputs(lift.open1, 1, 1, if(remark.isBlank()) "1楼开门到位信号" else remark)?.getByte(0)?.toInt()
            ?: throw BusinessError("read lift $name open1 error")
        pass == 1
      }
      2 -> {
        val pass = liftModbusHelper
            .read02DiscreteInputs(lift.open2, 1, 1, if(remark.isBlank()) "2楼开门到位信号" else remark)?.getByte(0)?.toInt()
            ?: throw BusinessError("read lift $name open2 error")
        pass == 1
      }
      else -> throw BusinessError("No such floor $floor")
    }
  }

  fun onExit() {
    val lift = CUSTOM_CONFIG.myLifts.findLast { it.name == name } ?: throw BusinessError("No such Lift Config $name")
    liftModbusHelper.write05SingleCoil(lift.floor1, false, 1, "OUT1 OFF")
    liftModbusHelper.write05SingleCoil(lift.floor2, false, 1, "OUT3 OFF")
    liftModbusHelper.write05SingleCoil(lift.inPlace, false, 1, "OUT2 OFF")
    logger.debug("reset lift $name")
  }
}