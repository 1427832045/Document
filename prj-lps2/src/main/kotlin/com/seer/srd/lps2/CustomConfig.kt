package com.seer.srd.lps2

import com.seer.srd.util.loadConfig

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

class CustomConfig {

  var workStations = mutableListOf(
    "311M", "311N", "311O", "311P", "311Q", "311R", "311S", "311T", "311U", "311V", "311W", "311X"
    ).apply {
      for (str in "ABCDEFGHIJKLMNOPQRSTUVWXYZ") {
        this.add("312$str")
        this.add("313$str")
        this.add("314$str")
        if (str in "ABCDEFGHIJKL") this.add("315$str")
      }
    }

  var left312 = mutableListOf("312R", "312I", "312Q", "312J", "312H")

  var left314 = mutableListOf("314A", "314X", "314B", "314W", "314C", "314Y")

  var right312 = mutableListOf("312K", "312L", "312M", "312N", "312O", "312P")

  var right314 = mutableListOf("314D", "314E", "314F", "314G", "314T", "314U", "314V")

  var addStations = listOf<String>()

  var mockDi = false

  var interval = 5

  var descending = true

  var load = "load6"
  var take = "take4"
}