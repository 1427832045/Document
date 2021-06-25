package com.seer.srd.pager

import com.seer.srd.util.loadConfig

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

class CustomConfig {
  var tcpPort: Int = 12345
  var version: String = "1"
  var command: String = "3"
  var frameHead =  "aa"
  var frameTail = "55"
  var concentrators: List<Concentrator> = emptyList()
}


data class Concentrator(
    var id: String = "",
    var nodes: List<PagerNode> = emptyList()
)
data class PagerNode(
    var id: String = "",
    var location: String = ""
//    var light: String? = "",
//    var buzzer: Boolean? = false,
//    var preservedAddr1: String? = "",
//    var preservedAddr2: String? = "",
//    var preservedAddr3: String? = ""
)
//{
//  override fun equals(other: Any?): Boolean {
//    if (this === other) return true
//    if (javaClass != other?.javaClass) return false
//
//    other as PagerNode
//
//    if (id != other.id) return false
////    if (location != other.location) return false
////    if (light != other.light) return false
////    if (buzzer != other.buzzer) return false
////    if (preservedAddr1 != other.preservedAddr1) return false
////    if (preservedAddr2 != other.preservedAddr2) return false
////    if (preservedAddr3 != other.preservedAddr3) return false
//    return true
//  }
//
//  override fun hashCode(): Int {
//    var result = id.hashCode()
////    result = 31 * result + location.hashCode()
////    result = 31 * result + light.hashCode()
////    result = 31 * result + buzzer.hashCode()
////    result = 31 * result + preservedAddr1.hashCode()
////    result = 31 * result + preservedAddr2.hashCode()
////    result = 31 * result + preservedAddr3.hashCode()
//    return result
//  }
//
//}
