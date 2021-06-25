package com.seer.srd.tomi

import com.seer.srd.util.HttpClient
import com.seer.srd.util.loadConfig
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.POST
import retrofit2.http.Query

object UrlBuilder {
    private const val appKey = "017085c1-f58b-40f2-bd4d-a21086558534"
    const val apiNameFeedback = "dispatchingInformationFeedback"
    const val apiNameErrorReport = "alarmInformationAcceptance"
    val config = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()
    private val tssBaseUrl = "http://${config.tssIp}:${config.tssPort}/Thingworx/Things/TOMI.SEEDIntegratedServices.Thing/Services/"

    const val commonEntries = "?appKey=$appKey&method=post&Accept=application/json"
//
//    fun getFeedbackUrl(taskId: String, vehicleId: String) = "$tssBaseUrl/$apiNameFeedback$commonEntries&taskId=$taskId&agvId=$vehicleId"
//
//    fun getErrorReportUrl(vehicleId: String, errorMessage: String) = "$tssBaseUrl/$apiNameErrorReport$commonEntries&agvId=$vehicleId&errorMessage=${URLEncoder.encode(errorMessage, StandardCharsets.UTF_8)}"

    val tssClient = HttpClient.buildHttpClient(tssBaseUrl, TSSClient::class.java)
}

interface TSSClient {
    @POST("${UrlBuilder.apiNameFeedback}${UrlBuilder.commonEntries}")
    fun feedback(@Query("taskId") taskId: String, @Query("agvId") agvId: String): Call<ResponseBody>

    @POST("${UrlBuilder.apiNameErrorReport}${UrlBuilder.commonEntries}")
    fun reportError(@Query("agvId") agvId: String, @Query("errorMessage", encoded = true) errorMessage: String): Call<ResponseBody>
}

data class CustomConfig(var tssIp: String = "localhost", var tssPort: Int = 7878)