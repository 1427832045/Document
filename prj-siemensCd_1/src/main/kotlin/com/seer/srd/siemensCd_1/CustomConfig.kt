package com.seer.srd.siemensCd_1

import com.seer.srd.util.loadConfig

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

class CustomConfig {
  var emptyTrayTaskTrigger = true
  var emptyTypeSent = ""
  var emptyTypeRev = ""
  var productTypeSent = ""
  var productTypeRev = ""
  var matTypeSent = ""
  var matTypeRev = ""
  var matTypeIn = ""
  var matTypeOut = ""
}