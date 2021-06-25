package com.seer.srd.runhua

import com.seer.srd.util.loadConfig

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

class CustomConfig {
  var plcConfig: Map<String, PLCModBusConfig> = emptyMap()
  var plcAddress = ModBusAddress()
  var interval = 5000

  // 起点对应的终点
  var fromToMap = mapOf("A" to "TO-A", "B" to "TO-A", "C" to "TO-A", "D" to "TO-D")

}

class PLCModBusConfig {
  var host: String = "127.0.0.1"
  var port: Int = 502
}

class ModBusAddress {

  var callA = 1
  var canLeaveA = 2
  var canEndA = 3
  var calledA = 1
  var onPositionFromA = 2
  var onPositionToA = 3

  var callB = 11
  var canLeaveB = 12
  var canEndB = 13
  var calledB = 11
  var onPositionFromB = 12
  var onPositionToB = 13


  var callC = 21
  var canLeaveC = 22
  var canEndC = 23
  var calledC = 21
  var onPositionFromC = 22
  var onPositionToC = 23


  var callD = 31
  var canLeaveD = 32
  var canEndD = 33
  var calledD = 31
  var onPositionFromD = 32
  var onPositionToD = 33






}