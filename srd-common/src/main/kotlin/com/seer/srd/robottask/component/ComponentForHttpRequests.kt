@file:Suppress("BooleanLiteralArgument")

package com.seer.srd.robottask.component

import com.seer.srd.BusinessError
import com.seer.srd.Error400
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

private val LOG = LoggerFactory.getLogger("com.seer.srd.robottask")

val httpRequestComponents: List<TaskComponentDef> = listOf(
    TaskComponentDef(
        "api", "RequestTargetByGet", "请求目标服务器[GET]", "",
        false, listOf(
        TaskComponentParam("description", "请求的描述", "string"),
        TaskComponentParam("url", "url", "string")
    ), true
    ) { component, ctx ->
        val description = parseComponentParamValue("description", component, ctx) as String
        val url = parseComponentParamValue("url", component, ctx) as String
        if (url.isBlank()) throw BusinessError("input url first!!!")
        try {
            val res = execute(description, "GET", url)
            ctx.setRuntimeVariable(component.returnName, res)
        }catch (e: Exception) {
            ctx.setRuntimeVariable(component.returnName, "$e")
            throw e
        }
    },
    TaskComponentDef(
        "api", "RequestTargetByPost", "请求目标服务器[POST]", "",
        false, listOf(
        TaskComponentParam("description", "请求的描述", "string"),
        TaskComponentParam("url", "url", "string"),
        TaskComponentParam("reqBody", "请求正文(JSON/String)", "string")
    ), true
    ) { component, ctx ->
        val description = parseComponentParamValue("description", component, ctx) as String
        val url = parseComponentParamValue("url", component, ctx) as String
        val reqBody = parseComponentParamValue("reqBody", component, ctx) as String
        if (url.isBlank()) throw BusinessError("input url first!!!")
        try {
            val res = execute(description, "POST", url, RequestBody.create(MediaType.get("application/json"), reqBody))
            ctx.setRuntimeVariable(component.returnName, res)
        } catch (e: Exception) {
            // 给前端抛出完整的异常信息
            ctx.setRuntimeVariable(component.returnName, "$e")
            throw e
        }
    }
)

fun execute(description: String, method: String, url: String, body: RequestBody? = null): String {
    val methodUpperCase = method.toUpperCase()
    if (methodUpperCase !in listOf("GET", "POST")) throw BusinessError("UnsupportedMethod[$method]")
    try {
        val client = OkHttpClient.Builder().connectTimeout(3000L, TimeUnit.MILLISECONDS).build()
        val request = Request.Builder()
            .method(methodUpperCase, if (methodUpperCase == "GET") null else body)
            .url(url)
            .addHeader("content-type", "application/json")
            .addHeader("accept", "application/json")
            .build()
        val call = client.newCall(request)
        val response = call.execute()
        val resCode = response.code()
        if (resCode > 300) throw BusinessError("RequestFailed, ResCode=$resCode")
        val resBodyString = response.body()?.string()
            ?: """{"code":"EmptyResponseBody", "message":"EmptyResponseBody"}"""
        LOG.debug("$description $method ${request.url()}, ReqBody=$body, " +
            "ResCode=${resCode}, message=${response.message()}, ResBody=$resBodyString .")
        return resBodyString

    } catch (e: Exception) {
        LOG.error("[RequestTargetFailed] $description [$method] url=$url, ReqBody=$body, because $e")
        throw Error400("RequestTargetFailed", e.message)
    }
}