package com.seer.srd.festo

data class CustomConfig(
    var limitOfSentTasks4: Int = 4
)

data class PdaRequestBody(
    val menuId: String = "",
    val workType: String = "",
    val workStation: String = "",
    val params: Any? = null
)

data class UpdateSiteParams(
    val siteId: String? = null,
    val changeToFilled: Boolean? = null
)

data class FillEmptyTrayParams(
    val fillByType: String? = null
)

data class ComplexTaskParams(
    val type: String? = null,
    val toSiteId: String? = null,
    val fromSiteId: String? = null
)

// Complex 任务的 persistedVariables
data class ComplexTaskPvs(
    val type: String? = null,                   // Both: 顺风车任务， Empty: 空托盘任务， Filled: 满托盘任务
    val sent: String? = null,                   // "sent" 表示此（Complex）任务已经被下发； 否则此任务还未被下发
    val fromSiteIdEmpty: String? = null,        // 空托盘任务的起点
    val toSiteIdEmpty: String? = null,          // 空托盘任务的终点
    val fromSiteIdFilled: String? = null,       // 满托盘任务的起点
    val toSiteIdFilled: String? = null          // 满托盘任务的终点
)
