package com.seer.srd.huaxin.hb

import com.seer.srd.util.loadConfig

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

class CustomConfig {

  var mesUrl: String = "http://localhost:7100/api/"

  var waitKeyLoad = 20
  var waitKeyUnLoad = 19

  var host = "127.0.0.1"
  var port = 502
  var onPositionAddr = 10
  var fromAddr = 11
  var finishAddr = 12
  var unitId = 1

  var lsMap= mapOf(
      "分拣台" to 1, "工程区存放区" to 2, "烘干区" to 3, "测试1" to 1, "测试2" to 2,
      "测试3" to 3, "测试4" to 4, "测试5" to 5, "测试6" to 6, "测试7" to 7, "测试8" to 8,
      "测试9" to 9, "测试10" to 10, "测试11" to 11, "临放" to 12, "分拣" to 13, "计量" to 14
      )

  var taskDefs: List<String> = listOf(
      "HB_Load",
      "HB_JX2QX",
      "HB_JX2CP",
      "HB_ZP2YS",
      "HB_FJ2PW",
      "HB_FJ2JX",
      "HB_PQ2ZP",
      "HB_AS2FJ",
      "HB_AS2ZC",
      "HB_AS2GCS",
      "HB_AS2ZCS",
      "HB_D_GC2LS",
      "HB_D_FJ2LS",
      "HB_D_HG2LS",
      "HB_D_FJ2DZ",
      "HB_D_FJ2ZH",
      "HB_D_GC2ZH",
      "HB_BJ_AS2O",
      "HB_BJ_O2LS",
      "HB_BJ_UNIVERSAL",
      "HB_BJ_UNIVERSAL2"
  )
  var extraTaskDefs: List<String> = emptyList()
}