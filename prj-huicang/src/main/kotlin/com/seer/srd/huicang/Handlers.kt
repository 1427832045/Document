package com.seer.srd.huicang

import com.seer.srd.http.Handlers
import com.seer.srd.http.ReqMeta
import io.javalin.http.Context
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.seer.srd.huicang")

fun registerDefaultHttpHandlers() {

    val operator = Handlers("ext/sim")
    // 模拟WMS接收回传完成信号，用于本地测试
    operator.post("wms/black/order/finished", ::taskFinished, ReqMeta(test = true, auth = false))
}

fun taskFinished(ctx: Context) {
    val body = ctx.body()
    logger.debug("task finished [sim]: data=$body ")
    ctx.json("""{"success":"true"}""")
    ctx.status(201)
}