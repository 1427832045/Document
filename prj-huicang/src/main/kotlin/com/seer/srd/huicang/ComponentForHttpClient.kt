package com.seer.srd.huicang

import com.seer.srd.BusinessError
import com.seer.srd.Error400
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import okhttp3.*
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

private val LOG = LoggerFactory.getLogger("com.seer.srd.huicang")

val httpClientComponents: List<TaskComponentDef> = listOf(
    TaskComponentDef(
        "extra", "RequestRemoteByGet", "请求目标服务器[GET]", "",
        false, listOf(
        TaskComponentParam("description", "请求的描述", "string"),
        TaskComponentParam("url", "url", "string")
    ), true
    ) { component, ctx ->
        val description = parseComponentParamValue("description", component, ctx) as String
        val url = parseComponentParamValue("url", component, ctx) as String
        if (url.isBlank()) throw BusinessError("input url first!!!")

        val res = execute(description, "GET", url)

        ctx.setRuntimeVariable(component.returnName, res)
    },
    TaskComponentDef(
        "extra", "RequestRemoteByPost", "请求目标服务器[POST]", "",
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

        val res = execute(description, "POST", url, RequestBody.create(MediaType.get("application/json"), reqBody))

        ctx.setRuntimeVariable(component.returnName, res)
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
        LOG.debug("$description $method ${request.url()}, " +
            "ResCode=${resCode}, message=${response.message()}, ResBody=$resBodyString .")

        return resBodyString
    } catch (e: Exception) {
        LOG.error("RequestRemoteFailed for $description because $e")
        throw Error400("RequestRemoteFailed", e.message)
    }
}

fun enqueue(url: String) {
    val okHttpClient = OkHttpClient()
    val request = Request.Builder().url(url).method("GET", null).build()
    val call = okHttpClient.newCall(request)
    call.enqueue(object : Callback {
        override fun onFailure(p0: Call, p1: IOException) {
            LOG.info("request failed !!! $p1")
        }

        override fun onResponse(p0: Call, p1: Response) {
            val resBodyString = p1.body()?.string() ?: """{"code":"EmptyResponseBody", "message":"EmptyResponseBody"}"""
            LOG.info("Res Code: ${p1.code()}, message: ${p1.message()}, body:$resBodyString")
        }
    })
}

