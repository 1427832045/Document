package com.seer.srd.proface

import com.seer.srd.util.loadConfig

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

class CustomConfig {
  var fireHost = "127.0.0.1"
  var firePort = 502
  var triggerAddr = 1024
  var fireUnitId = 0
  var fire = true
  var finsClients: Map<String, FinsClient> = emptyMap()
  var srcHost: String = "127.0.0.1"
  var srcNetAddr: Int = 0
  var srcNodeAddr: Int = 0x01
  var srcUnitAddr: Int = 0
  var mzToLoc: Map<String, List<Int>> = emptyMap()
  var srcPort: Int = 9600
  var finsTrigger = true
  var siteRange = 6
}

class FinsClient {
  var srcPort: Int = 9600
  var desHost: String = "127.0.0.1"
  var desPort: Int = 9600
  var desNetAddr: Int = 0
  var desNodeAddr: Int = 0x01
  var desUnitAddr: Int = 0
  var info: ClientInfo = ClientInfo()
}

class ClientInfo {
  var area: Int = 0x82
  var readAddr: Int = 0
  var readOffset: Int = 0
  var writeAddr: Int = 0
  var writeOffset: Int = 0
  var count: Int = 1
}