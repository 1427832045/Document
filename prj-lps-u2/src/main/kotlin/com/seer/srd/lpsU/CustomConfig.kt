package com.seer.srd.lpsU

import com.seer.srd.util.loadConfig

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

class CustomConfig {
    var tcpPort: Int = 0
    var productTypes: List<SelectOption> = emptyList()
    var modbusLines: Map<String, ModbusLine> = emptyMap()
//    var modbusAddress = ModbusAddress()
    var bufferAddress = BufferAddress()
    var flipCInterval = 1000L
    var urModbusHost = "127.0.0.1"
    var urModbusPort = 502
    var bufferCheckInterval = 60000L
    var downSize = 6
    var checkDownSize = 5
    var leastDownSize = 1
    var upSize = 6

    var multiCar = true

    var versionHost = "http://127.0.0.1:7100/api/mock/"

    var upCarCheckInterval = 30

    var retryTimes = 30
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

//class ModbusAddress {
//    var upBin1: Int = 0
//    var upBin2: Int = 0
//    var upBin3: Int = 0
//    var upA: Int = 0
//    var upB: Int = 0
//    var upC: Int = 0
//
//    var downBin1: Int = 0
//    var downBin2: Int = 0
//    var downA: Int = 0
//    var downB: Int = 0
//    var downC: Int = 0
//
//    var alert: Int = 0
//}

class BufferAddress {
    var urUpOperationAddr = 0
    var urDownOperationAddr = 1
    var matNumAddr = 2
    var bufferUpOperationAddr = 3
    var bufferDownOperationAddr = 4
    var errorCodeAddr = 5
    var bufferOneAddr = 6
    var checkAddr = 7
    var checkOKAddr = 8
    var lightAddr = 0
    var standByAddr = 10
}