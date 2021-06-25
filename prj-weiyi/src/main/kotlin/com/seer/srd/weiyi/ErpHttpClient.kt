package com.seer.srd.weiyi

import com.fasterxml.jackson.databind.JsonNode
import retrofit2.Call
import retrofit2.http.*

@JvmSuppressWildcards
interface ErpHttpClient {

    // SRD 请求 ERP，查询产品信息
    @GET("product-info")
    fun productInfoStr(@Query("code") code: String): Call<String>

    // SRD 请求 ERP，查询产品信息
    @GET("product-out-task")
    fun productOutTaskInfo(@Query("code") code: String): Call<String>

    // SRD 请求 ERP，告知产品入库
    @POST("product-store-in")
    fun productStoreIn(@Body info: JsonNode): Call<Void>

    // SRD 请求 ERP，报告出库完成
    @POST("product-store-out")
    fun productStoreOut(@Body info: JsonNode): Call<Void>

    // SRD 请求 ERP，告知理库完成
    @POST("product-sort")
    fun productSortFinished(@Body info: JsonNode): Call<Void>
}

class CustomConfig {
    var erpUrl: String = "http://localhost:7020/ext/"
    var startTime: String = "22:00:00"                                  // "2020-05-15T22:00:00.000Z"
    var endTime: EndTime = EndTime()
    var authPwd: String = "auth-weiyi"                                  // 启用/禁用自动理库的授权密码
    var maxColumnCountsOfD = 60                                         // D区 每行库位的最大数量
    var columnIdAndCountsOfD: MutableMap<String, ColumeConfig> = mutableMapOf()  // D区 个行库位的实际数量，无特殊要求可不配置, map<columnId, counts>
    var siteCountsOfE = 20                                              // E区 库位总数
    var maxExecNumOfProductIn = 3                                       // 入库任务最多3辆车执行
    var maxExecNumOfProductOut = 5                                      // 出库任务最多5辆车执行
}

class EndTime {
    var time: String = "04:00:00"           // "2020-05-16T04:00:00.000Z"
    var nextDay: Boolean = true
}

data class ColumeConfig(
    var total: Int = 60,
    var unusedIndexList: List<Int>? = null
)