package com.seer.srd.dupu

import com.seer.srd.util.loadConfig

object CommonUtils {
    val customConfig = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()
}

class CustomConfig {
    var adminPwd: String = "auth-dp"               // 管理员权限密码
    var specialSites: List<String> = emptyList()   // 特殊库位
    var workStationConfigs: Map<String, WorkStationConfig> = emptyMap()    // 工作站 - 指定机器人
    var chargerRoutes: List<String> = emptyList()   // 前往充电站的路径
}

data class WorkStationConfig(
    var vehicle: String = "",
    var front: String = "",
    var next: String = "",
    var park: String = ""
)