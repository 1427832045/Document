package com.seer.srd.huaxin

import com.seer.srd.util.loadConfig

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

class CustomConfig {

  var taskDefToSiteVehicle: Map<String, List<SiteToVehicle>> = mapOf(
      "prefix-in" to listOf(
          SiteToVehicle(
              from = listOf("ZX-01"), to = listOf("ZX-02"), vehicleName = "SW500-01"
          )
      ),
      "machine-test" to listOf(
          SiteToVehicle(
              from = listOf("ZX-01", "ZX-02"), to = listOf("YS-01", "YS-02"), vehicleName = "SW500-01"
          ),
          SiteToVehicle(
              from = listOf("ZP-01", "ZP-02", "ZP-03", "ZP-04", "ZP-05", "ZP-06"),
              to = listOf("YS-01", "YS-02", "YS-03", "YS-04", "YS-05"),
              vehicleName = "SW500-02"
          ),
          SiteToVehicle(
              from = listOf("YS-01", "YS-02", "YS-03", "YS-04", "YS-05"),
              to = listOf("ZP-01", "ZP-02", "ZP-03", "ZP-04", "ZP-05", "ZP-06"),
              vehicleName = "SW500-02"
          )
      ),
      "machine-disassemble" to listOf(
          SiteToVehicle(
              from = listOf("ZX-01", "ZX-02", "YS-01", "YS-02"), to = listOf("FJ-01"), vehicleName = "SW500-01"
          )
      ),
      "product-in" to listOf(
          SiteToVehicle(
              from = listOf("YS-01"), to = listOf("LK-01", "LK-03"), vehicleName = "SW500-01"
          ),
          SiteToVehicle(
              from = listOf("YS-02"), to = listOf("LK-02"), vehicleName = "SW500-01"
          ),
          SiteToVehicle(
              from = listOf("YS-03", "YS-04", "YS-05"), to = listOf("LK-01", "LK-03"), vehicleName = "SW500-02"
          )
      ),
      "machine-rework" to listOf(
          SiteToVehicle(
              from = listOf("YS-01"), to = listOf("ZP-01", "ZP-02", "ZP-03", "ZP-04", "ZP-05", "ZP-06"), vehicleName = "SW500-01"
          ),
          SiteToVehicle(
              from = listOf("ZP-01", "ZP-02", "ZP-03", "ZP-04", "ZP-05", "ZP-06"), to = listOf("YS-01"), vehicleName = "SW500-01"
          ),
          SiteToVehicle(
              from = listOf("YS-03", "YS-04", "YS-05"), to = listOf("ZP-01", "ZP-02", "ZP-03", "ZP-04", "ZP-05", "ZP-06"), vehicleName = "SW500-02"
          ),
          SiteToVehicle(
              from = listOf("ZP-01", "ZP-02", "ZP-03", "ZP-04", "ZP-05", "ZP-06"), to = listOf("YS-03", "YS-04", "YS-05"), vehicleName = "SW500-02"
          )
      ),
      "component-in" to listOf(
          SiteToVehicle(
              from = listOf("QX-01"), to = listOf("LK-01", "LK-03"), vehicleName = "SW500-01"
          )
      ),
      "component-fix" to listOf(
          SiteToVehicle(
              from = listOf("QX-01"),
              to = listOf("JX-01", "JX-02", "JX-03", "JX-04", "JX-05", "JX-06", "JX-07", "JX-08", "JX-09", "JX-10", "JX-11"),
              vehicleName = "SW500-01"
          )
      ),
      "product-out" to listOf(
          SiteToVehicle(
              from = listOf("LK-01", "LK-02", "LK-03"), to = listOf("ZX-01"), vehicleName = "SW500-01"
          )
      ),
      "relay-in-out" to listOf(
          SiteToVehicle(
              from = listOf("FS-01"), to = listOf("KC-01"), vehicleName = "SW500-03"
          ),
          SiteToVehicle(
              from = listOf("KC-01"), to = listOf("FS-01"), vehicleName = "SW500-03"
          )
      ),
      "relay-fix" to listOf(
          SiteToVehicle(
              from = listOf("KX-01", "KX-02", "KX-03"),
              to = listOf("XC-01", "XC-02", "XC-03", "XC-04", "XC-05", "XC-06", "XC-07", "XC-08",
                  "XC-09","XC-10", "XC-11", "XC-12", "XC-13", "XC-14", "XC-15", "XC-16"),
              vehicleName = "SW500-03"
          )
      ),
      "relay-check" to listOf(
          SiteToVehicle(
              from = listOf("YC-01", "YC-02", "YC-03", "YC-04"),
              to = listOf("XC-01", "XC-02", "XC-03", "XC-04", "XC-05", "XC-06", "XC-07", "XC-08",
                  "XC-09","XC-10", "XC-11", "XC-12", "XC-13", "XC-14", "XC-15", "XC-16"),
              vehicleName = "SW500-03"
          ),
          SiteToVehicle(
              to = listOf("YC-01", "YC-02", "YC-03", "YC-04"),
              from = listOf("XC-01", "XC-02", "XC-03", "XC-04", "XC-05", "XC-06", "XC-07", "XC-08",
                  "XC-09","XC-10", "XC-11", "XC-12", "XC-13", "XC-14", "XC-15", "XC-16"),
              vehicleName = "SW500-03"
          )
      ),
      "relay-product-in" to listOf(
          SiteToVehicle(
              from = listOf("YC-01", "YC-02", "YC-03", "YC-04"), to = listOf("KC-01"), vehicleName = "SW500-03"
          )
      )
  )

  var extraTaskDefToSiteVehicle: Map<String, List<SiteToVehicle>> = emptyMap()

//  var vehicleToTaskDefMap: Map<String, List<TaskToSite>> = mapOf(
//      "SW500-01" to listOf(
//          TaskToSite(
//              taskDefName = "component-fix",
//              from = listOf("QX-01"),
//              to = listOf("JX-01", "JX-02", "JX-03", "JX-04", "JX-05", "JX-06", "JX-07", "JX-08", "JX-09", "JX-10", "JX-11")
//          ),
//          TaskToSite(
//              taskDefName = "machine-test",
//              from = listOf("ZX-01", "ZX-02"),
//              to = listOf("YS-01", "YS-02")
//          )
//      ),
//      "SW500-02" to listOf(),
//      "SW500-03" to listOf()
//  )
//  var taskDefsToSite: Map<String, List<String>> = mapOf(
//      "component-fix" to listOf(),          // 部件检修
//      "component-in" to listOf(""),         // 部件入库
//      "machine-disassemble" to listOf(""),  // 整机分解
//      "machine-rework" to listOf(""),       // 整机返工
//      "machine-test" to listOf(""),         // 整机测试
//      "prefix-in" to listOf(""),            // 待修品入库
//      "product-in" to listOf(""),           // 成品入库
//      "product-out" to listOf(""),          // 成品出库
//      "relay-check" to listOf(""),          // 继电器验收
//      "relay-fix" to listOf(""),            // 继电器检修
//      "relay-in-out" to listOf(""),         // 继电器返所/入所
//      "relay-product-in" to listOf("")      // 继电器成品入库
//  )

//  var extraTaskDefsToSite: Map<String, List<String>> = emptyMap()

  var taskDefs: List<String> = listOf(
      "component-fix",          // 部件检修
      "component-in",         // 部件入库
      "machine-disassemble",  // 整机分解
      "machine-rework",       // 整机返工
      "machine-test",         // 整机测试
      "prefix-in",            // 待修品入库
      "product-in",           // 成品入库
      "product-out",          // 成品出库
      "relay-check",          // 继电器验收
      "relay-fix",            // 继电器检修
      "relay-in-out",         // 继电器返所/入所
      "relay-product-in"      // 继电器成品入库
  )
  var extraTaskDefs: List<String> = emptyList()
}

//class TaskToSite (
//    var taskDefName: String = "",
//    var from: List<String> = emptyList(),
//    var to: List<String> = emptyList()
//)

class SiteToVehicle(
    var from: List<String> = emptyList(),
    var to: List<String> = emptyList(),
    var vehicleName: String = ""
)