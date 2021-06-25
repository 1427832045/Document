package com.seer.srd.handler

import com.seer.srd.CONFIG
import com.seer.srd.http.HttpServer
import io.javalin.http.Context

// 列出所有 API 接口的信息
fun handleListApi(ctx: Context) {
    val list = HttpServer.mappings
        .filter { !it.meta.page && it.meta.test }
        .map {
            val apiPrefix = if (it.withoutApiPrefix) "" else CONFIG.apiPrefix
            val url = "/" + apiPrefix + "/" + it.path
            mapOf(
                "method" to it.method.toString(),
                "url" to url.replace(Regex("/{2,}"), "/"),
                "reqBodyDemo" to it.meta.reqBodyDemo
            )
        }
    ctx.json(list)
}
