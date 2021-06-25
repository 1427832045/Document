package com.seer.srd.huaxin

import com.seer.srd.util.loadConfig

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

class CustomConfig {

  var mesUrl: String = "http://localhost:7100/api/"

  var waitKeyLoad = 20
  var waitKeyUnLoad = 19

  var preSendSignal = false

  var taskDefs: List<String> = listOf(
      "1F_ASAToAssemble8To14",                  // 立库A到装配区8-14
      "1F_ASAToFix",                            // 立库A到检修区
      "1F_ASToFix",                             // 立库A/B/C到检修区
      "1F_ASToAssemble1To7",                    // 立库B/C到装配区1-7
      "1F_BetweenAssemble",                     // 装配区1-7之间
      "1F_ASToTest",                            // 立库B/C到测试区
      "1F_ASToUnload",                          // 立库B/C到卸车区
      "1F_TestToLoad",                          // 测试区到装车区
      "1F_TestToUnload",                        // 测试区到卸车区
      "1F_FixToAssemble8To14",                  // 检修区到装配区8-14
      "1F_ASToAssemble8To14",                   // 立库A/B/C到装配区8-14
      "1F_ASToLoad",                            // 立库B/C到装车区
      "1F_ASToShield",                          // 立库B/C到罩壳区
      "1F_FixToClear",                          // 检修区到清洗区
      "1F_ShieldToAssemble",                    // 罩壳 <-> 装配区
      "1F_TestToAssemble",                      // 转撤机测试区 <-> 装配区

      "2F_ASToClear",                           // 接驳台到清洁区双向任务
      "2F_ASToSelfCloseFix",                    // 接驳台到自闭器检修双向任务
      "2F_ClearToElectricMaterialFix",          // 清洁区到电子器件检修双向任务
      "2F_ASToElectricMaterialFix",             // 接驳台到电子器件检修双向任务
      "2F_ASToBurnInRoom",                      // 接驳台到灯泡老化室双向任务

      "2F_ASToLine",                            // 接驳台到流水线首端单向任务
      "2F_ASToRelayCheck"                       // 接驳台到继电器验单双向任务
  )
  var extraTaskDefs: List<String> = emptyList()
}