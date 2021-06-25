package com.seer.srd.operator

data class OperatorConfig(
    var signIn: Boolean = false, // 是否需要登录
    var workStations: List<OperatorWorkStation> = emptyList(), // 工位
    var workTypes: List<OperatorWorkType> = emptyList(), // 岗位
    var orders: List<OperatorOrder> = emptyList(), // 呼叫运单
    var orderMenuItemHeight: Int? = null, // 下单菜单的菜单项高度，单位像素
    var orderMenuItemFontSize: Int? = null, // 下单菜单的菜单项文字大小，单位像素
    var orderMenuItemMargin: Int? = null, // 下单菜单的菜单项垂直外留白，单位像素
    var taskNotice: EventNotice? = null, // 事件通知配置
    var customConfig: Any? = null,  // 定制项目的额外配置
    var taskNoticeByTask: Map<String, EventNotice> = emptyMap()
)

/**
 * 工位
 */
data class OperatorWorkStation(
    var id: String = "",
    var label: String = "",
    var type: String = ""
)

/**
 * 岗位
 */
data class OperatorWorkType(
    var id: String = "",
    var label: String = ""
)

/**
 * 下单功能配置
 */
data class OperatorOrder(
    var menuId: String = "",
    var label: String = "",
    var menuItemBackground: String? = null, // 下单菜单项的背景色
    var menuItemTextColor: String? = null, // 下单菜单项的文字颜色
    var disabled: Boolean? = null,
    var robotTaskDef: String = "",
    var workTypes: List<String>? = null, // 按岗位控制可见性
    var workStations: List<String>? = null, // 按工位控制可见性
    var params: List<OperatorOrderParam> = emptyList(),
    var tip: String? = null, // 下单界面首部的提示
    var confirmMessage: String? = null // 确认消息，为空不确认
)

data class OperatorOrderParam(
    var name: String = "",
    var label: String = "",
    var input: String = "", // " text" | " textarea " | " select " | " list "
    var options: List<OperatorOrderParamOption>? = null,
    var optionsSource: String = "",
    var multiple: Int = 0, // 影响多行文本框的行数
    var dataId: String = "", // 数据（源）标识，用于给 options 一个唯一ID
    var inputDetails: String = ""
)

data class OperatorOrderParamOption(
    var value: String = "",
    var label: String = ""
)

data class EventNotice(
    var scope: String = "", // "all" | "by-work-type" | "by-work-station"
    var noticeType: String = "", // "toast" | "alert"
    var `when`: List<String> = emptyList() // ("created" | "updated" | "finished")[]
)