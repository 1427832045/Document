package com.seer.srd.junyi

import retrofit2.Call
import retrofit2.http.*

@JvmSuppressWildcards
interface MesHttpClient {

    // P030 放行操作，复用下壳体上线的放行接口，否则还需要跟对方商量新的接口
    // SRD 请求 MES，下壳体上线 - 放行
    @POST("load-bottom-shell-finished")
    fun loadBottomShellFinished(): Call<Void>

    // SRD 请求 MES，AGV到达模组入壳工位
    @POST("vehicle-at-load-module-pos")
    fun vehicleAtLoadModulePos(): Call<Void>

    // SRD 请求 MES，模组固定工位已经【呼叫】
    @POST("fix-module-already-called")
    fun fixModuleAlreadyCalled(): Call<Void>

    // SRD 请求 MES，AGV到达模组入壳的下一个工位(模组固定工位)
    @POST("vehicle-at-fix-module-pos")
    fun vehicleAtLoadModuleNextPos(): Call<Void>

    // SRD 请求 MES，上壳体安装 - 放行
    @POST("load-top-shell-finished")
    fun loadTopShellFinished(): Call<Void>

    // SRD 请求 MES，AGV到达螺丝紧固工位
    @POST("vehicle-at-fix-screw-pos")
    fun vehicleAtFixScrewPos(): Call<Void>

    // SRD 请求 MES，气密性检测工位已经【呼叫】
    @POST("air-tightness-test-already-called")
    fun airTightnessTestAlreadyCalled(): Call<Void>

    // SRD 请求 MES，AGV到达模组入壳的下一个工位(气密性检测工位)
    @POST("vehicle-at-air-tightness-test-pos")
    fun vehicleAtFixScrewNextPos(): Call<Void>

}

class CustomConfig {
    var mesUrl: String = "http://localhost:7010/api/ext/mock/"
    var password: String = "123456"
    var bufferForPopUpTask: List<String> = listOf("G-A", "G-B")
    var enablePopupTask: Boolean = false
}

