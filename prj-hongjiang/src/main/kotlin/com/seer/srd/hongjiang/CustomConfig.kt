package com.seer.srd.hongjiang

import com.seer.srd.util.loadConfig

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

class CustomConfig {
  var userConfig = emptyList<UserConfig>()
  var canWithdrawAll = false
}

class UserConfig {
  var workStation: String = ""
  var pwd: String = ""
  var relatives: List<String> = emptyList() //  不要删除
}