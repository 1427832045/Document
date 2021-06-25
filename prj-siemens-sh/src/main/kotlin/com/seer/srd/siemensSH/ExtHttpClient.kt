package com.seer.srd.siemensSH

import retrofit2.Call
import retrofit2.http.GET

@JvmSuppressWildcards
interface ExtHttpClient {

    // 向 siemens 的上位机查询 Z8码 和 800码 的对应关系
    @GET("code-map")
    fun getCodeMapFromSiemens(): Call<CodeMapResponse>

}