package com.seer.srd.huaxin

import com.seer.srd.util.loadConfig

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

class CustomConfig {
  var task: Map<String, Task> = emptyMap()
  var taskDefs = listOf(
      "PWToCircle",
      "productToPrefix",
      "clearToFix",
      "storeroomToFix",
      "storeToAssemble",
      "checkToProduct",
      "storeToFix"
  )
  var extraTaskDefs: List<String> = emptyList()
}

class Task {
  var site1 = emptyList<String>()
  var site2 = emptyList<String>()
}