package com.seer.srd.chinapost

import com.seer.srd.chinapost.plc.PlcConfig
import com.seer.srd.util.loadConfig

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

class CustomConfig {
  var plcDeviceList: List<PlcConfig> = listOf()
  var area: Map<String, ModBusSite> = emptyMap()
  var checkInterval: Long = 10000
  // errorMap<code, message>
  var errorMap: Map<Int, String>? = null
  var timeout11000 = 1000
  var timeout11001 = 2000
  var enabled = true
  var plcEnabled = true
}

class ModBusSite {
  var host = "127.0.0.1"
  var port = 502
  var siteToAddr: Map<String, Int> = emptyMap()
}