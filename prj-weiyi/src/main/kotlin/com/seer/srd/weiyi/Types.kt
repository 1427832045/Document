package com.seer.srd.weiyi

import org.bson.codecs.pojo.annotations.BsonId


data class InFinishedInfo(
    val FContractNo: String,    // 订字
    val FMONO: String,          // 工单号
    val FEmpName: String,       // 业务员名称
    val FCustName: String,      // 客户名称
    val FQtyPiece: String,      // 匹数
    val FQty: String,           // 米数
    val Sotre: String           // 终点库位
)

data class OutFinishedInfo(
    val FMONO: String,          // 工单号
    val fromSite: String        // 库位号
)

data class SortFinishedInfo(
    val FMONO: String,          // 工单号
    val oldSite: String,        // 旧库位
    val newSite: String         // 新库位
)


data class ProductInfo(
    var fcontractNo: String = "null",    // 订字
    var fmono: String = "null",          // 工单号
    var fempName: String = "null",       // 业务员名称
    var fcustName: String = "null",      // 客户名称
    var fqtyPiece: String = "null",      // 匹数
    var fqty: String = "null"            // 米数
)

data class TaskOutInfo(
    val fbillNo: String = "null",       // 出库单号
    val fempName: String = "null",      // 旧库位
    val fcustName: String = "null",     // 新库位
    val orders: List<Order>,            // 子单列表
    val executed: Boolean = false      // true: 已经取走； false: 未完成
)

data class Order(
    val fmono: String = "null",          // 工单号
    val fqty: String = "null"            // 匹数
)

// 此类与通信协议无关
data class SitesForSort(
    var fromSiteId: String = "", // 起点
    var toSiteId: String = ""   // 终点
)

// 需要持久化数据
data class SysPersistParams(
    @BsonId val id: String = "",
    var enableAutoSort: Boolean = true      // 启用自动理库
)

