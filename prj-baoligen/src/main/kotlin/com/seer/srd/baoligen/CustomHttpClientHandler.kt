package com.seer.srd.baoligen

import com.fasterxml.jackson.databind.JsonNode
import com.seer.srd.BusinessError
import com.seer.srd.util.HttpClient
import com.seer.srd.util.mapper
import io.javalin.plugin.json.JavalinJson
import org.slf4j.LoggerFactory
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST
import java.time.Instant

object CustomHttpClient {
    private val logger = LoggerFactory.getLogger(CustomHttpClient::class.java)

    private val customHttpClient = HttpClient.buildHttpClient(CUSTOM_CONFIG.customUrl, CustomHttpClientHandler::class.java)

    fun takeMatFinished(body: TakeMatFinished): String? {
        val json = mapper.readTree(JavalinJson.toJson(body))
        val res = customHttpClient.takeMatFinished(json).execute()
        val resCode = res.code()
        val errMsg = "info remote take-mat-finished failed"
        when (resCode) {
            201 -> {
                val resBody = res.body()
                logger.debug("info remote take-mat-finished success, ${resBody}.")
                return resBody
            }
            400 -> {
                val resBody = res.body()
                    ?: throw BusinessError("$errMsg No response body from remote!")
                val resBodyObj = mapper.readValue(resBody, CustomResponse::class.java)
                logger.debug("$errMsg because ${resBodyObj.message}, try again.")
            }
            else -> {
                val resBody = res.body()
                    ?: throw BusinessError("$errMsg No response body from remote!")
                val resBodyObj = mapper.readValue(resBody, CustomResponse::class.java)
                logger.debug("ResCode:$resCode, $errMsg because ${resBodyObj.message}, try again.")
            }
        }
        return null
    }

    fun taskFinished(body: TaskFinished): String? {
        val json = mapper.readTree(JavalinJson.toJson(body))
        val res = customHttpClient.taskFinished(json).execute()
        val resCode = res.code()
        val errMsg = "info remote task-finished failed"
        when (resCode) {
            201 -> {
                val resBody = res.body()
                logger.debug("info remote task-finished success, ${resBody}.")
                return resBody
            }
            400 -> {
                val resBody = res.body()
                    ?: throw BusinessError("$errMsg No response body from remote!")
                val resBodyObj = mapper.readValue(resBody, CustomResponse::class.java)
                logger.debug("$errMsg because ${resBodyObj.message}, try again.")
            }
            else -> {
                val resBody = res.body()
                    ?: throw BusinessError("$errMsg No response body from remote!")
                val resBodyObj = mapper.readValue(resBody, CustomResponse::class.java)
                logger.debug("ResCode:$resCode, $errMsg because ${resBodyObj.message}, try again.")
            }
        }
        return null
    }
}

@JvmSuppressWildcards
interface CustomHttpClientHandler {
    // AGV在起点完成取料
    @POST("take-mat-finished")
    fun takeMatFinished(@Body body: JsonNode): Call<String>

    // 任务完成
    @POST("task-finished")
    fun taskFinished(@Body body: JsonNode): Call<String>
}

data class CustomConfig(
    var customUrl: String = "http://localhost:7100/api/ext/v1/srd/mock/"
)

data class TakeMatFinished(
    val taskId: String = "",
    val agvName: String = "",
    val currentLocation: String = "",
    val status: String = "已经取走料桶",
    val currentTime: Instant = Instant.now()
)

data class TaskFinished(
    val taskId: String = "",
    val fromLocation: String = "",
    val endLocation: String = ""
)

data class CustomResponse(
    val success: Boolean = false,
    val message: String = ""
)
