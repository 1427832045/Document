package com.seer.srd.lps

import com.seer.srd.util.loadConfig

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

class CustomConfig {
    var tcpPort: Int = 0
    var productTypes: List<SelectOption> = emptyList()
    var modbusLines: Map<String, ModbusLine> = emptyMap()
    var modbusAddress = ModbusAddress()
    var flipCInterval = 1000L
    var urModbusHost = "127.0.0.1"
    var urModbusPort = 502
    var downSize = 2
}

class SelectOption {
    var value: String = ""
    var label: String = ""
}

class ModbusLine {
    var upHost: String = ""
    var upPort: Int = 0
    var downHost: String = ""
    var downPort: Int = 0
    var alertHost: String = ""
    var alertPort: Int = 0
}

class ModbusAddress {
    var upBin1: Int = 0
    var upBin2: Int = 0
    var upBin3: Int = 0
    var upA: Int = 0
    var upB: Int = 0
    var upC: Int = 0
    
    var downBin1: Int = 0
    var downBin2: Int = 0
    var downA: Int = 0
    var downB: Int = 0
    var downC: Int = 0
    
    var alert: Int = 0
}