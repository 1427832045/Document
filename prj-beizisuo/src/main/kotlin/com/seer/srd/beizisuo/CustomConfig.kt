package com.seer.srd.beizisuo

import com.seer.srd.util.loadConfig

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

class CustomConfig {
//  var pkgLen = 32
  var tcpPort = 505
  var dataFromLen = 5
//  var dataToLen = 9
//  var crateTaskLoadMachineStatus = 0
//  var crateTaskUnloadMachineStatus = 0
  var createTaskByStatus = 1
  var taskDefName = "transfer"
  var skipSameTaskType = true
  var taskTypeMap: Map<Int, SendData> = mapOf(
      1 to SendData(1, 1),
      2 to SendData(3, 2),
      3 to SendData(2, 3),
      4 to SendData(5, 5),
      5 to SendData(4, 4)
  )
  var newTcpAdapter = true
}

class SendData(
  var from: Int = 0,
  var to: Int = 0
)
