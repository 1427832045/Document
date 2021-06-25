package com.seer.srd.jinfeng

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

@JvmSuppressWildcards
interface ErpHttpClient {
    @POST("agv/TaskStatus")
    fun tellErp(@Body body: Map<String, String?>): Call<String>
}

/*interface SelfClient {
    //调用柔性任务
    @POST("ext/goExchangeStation")
    fun callSelfInterface(@Body body: Map<String, String?>): Call<String>
}*/

class PickResult(
    var Status: Int = -1,
    var ErrorInfo: String = "",
    var Data: String? = null

//    var pass: Boolean = false
)

class ErpBody(
    var TaskId: String,
    var TaskStatus: String,
    var AgvNo: String?
)

object ErpBodyState {
    const val beforeCreated = "0" // 任务在系统内被创建之前
    const val created = "1" // 任务在系统内被创建
    const val waitPre = "2" // 机器人到达起点的前置点，准备去起点
    const val started = "3" // 机器人到达起点，抬起货架/托盘
    const val finished = "4" // 任务结束
    const val terminated = "5" //任务被手动终止
    const val failed = "6" //任务失败

    val stateList = listOf(created, started, waitPre, finished, terminated, failed)

}