package com.seer.srd.molex

import com.seer.srd.molex.stat.DayPartDef
import com.seer.srd.util.loadConfig

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

class CustomConfig {
  var areaToCabinet: Map<String, Cabinet> = emptyMap()
  var fromSites: List<String> = Services.getFromSites()
  var toSites: List<String> = Services.getToSites()
  var twinkle: Long = 500
  var interval = 5
  var noSensorList: List<String> = emptyList()
  var moshiStatDayPartDefs: List<DayPartDef> = emptyList()
  var readInterval = 5
  var lockFrom = true
  var taskSentListCapacity = 2
  var recognizeTasks = listOf("ST01", "ST02", "ST03")
  var palletTaskType = listOf("MD05-01", "MD05-02", "M6-01", "M6-02", "ST01", "ST02", "ST03")
  var fireWaitTrigger = true
  var palletDef = "palletTransfer"
  var transferDef = "transfer"
  var twinkleAllRed = false

  var preFromIndex = 0
  var fromIndex = 1
  var preToIndex = 2
  var toIndex = 3
  var enableMultiControl = false
  var checkBeforeFinal = true
}

class Cabinet {
  var host: String = "localhost"
  var port: Int = 502
  var siteIdToAddress: Map<String, ModbusAddress> = emptyMap()
  var fromAddr = 1280
}

class ModbusAddress: Comparable<ModbusAddress> {
  var index: Int = 0
  var red: Int = 1280
  var yellow: Int = 1281
  var green: Int = 1282
  var sensor1: Int = 1024
  var sensor2: Int = 1025

  override fun compareTo(other: ModbusAddress): Int {
    return when {
      this.red < other.red -> -1
      this.red > other.red -> 1
      else -> 0
    }
  }
}
