package com.seer.srd.moshi

import com.seer.srd.moshi.stat.DayPartDef
import com.seer.srd.util.loadConfig

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

class CustomConfig {
  var areaToCabinet: Map<String, Cabinet> = emptyMap()
  var fromSites: List<String> = Services.getFromSites()
  var toSites: List<String> = Services.getToSites()
  var twinkle: Long = 500
  var interval = 5
  var others: List<String> = emptyList()
  var moshiStatDayPartDefs: List<DayPartDef> = emptyList()
}

class Cabinet {
  var host: String = "localhost"
  var port: Int = 502
  var siteIdToAddress: Map<String, ModbusAddress> = emptyMap()
}

class ModbusAddress {
  var index: Int = 0
  var red: Int = 1280
  var yellow: Int = 1281
  var green: Int = 1282
  var sensor1: Int = 1024
  var sensor2: Int = 1025
}
