package com.seer.srd.lps3

import com.seer.srd.util.loadConfig

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

class CustomConfig {
  var myLifts: List<LiftConfig> = emptyList()
  var liftStatusPollingPeriod = 500L    // 呼叫电梯到达指定楼层的呼叫间隔
  var readLiftTimes = 3           // 呼叫电梯到达指定楼层的次数
}

class LiftConfig {
  var name = ""
  var host = "127.0.0.1"
  var port = 502
  var floor1 = 17
  var floor2 = 19
  var inPlace = 18
  var writeFuncCode = "05"

  var open1 = 1
  var open2 = 3
  var readFuncCode = "02"
}