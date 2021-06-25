package com.seer.srd.wangyue

import com.seer.srd.util.loadConfig

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

class  CustomConfig {

  var host = "127.0.0.1"
  var port = 502

  var functionCode = "4x"

  var listenAddr = 0

  var onPositionAddrA = 0
  var completeAddrA = 1

  var onPositionAddrB = 2
  var completeAddrB = 3

  var onPositionAddrC = 4
  var completeAddrC = 5

  var onPositionAddrD = 6
  var completeAddrD = 7

  var lineOperationAddrA = 10
  var canLeaveAddrA = 11

  var lineOperationAddrB = 12
  var canLeaveAddrB = 13

  var lineOperationAddrC = 14
  var canLeaveAddrC = 15

  var lineOperationAddrD = 16
  var canLeaveAddrD = 17
}
