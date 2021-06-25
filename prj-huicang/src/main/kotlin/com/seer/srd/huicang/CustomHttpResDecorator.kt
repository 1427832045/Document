package com.seer.srd.huicang

import com.seer.srd.robottask.HttpResponseDecorator
import com.seer.srd.robottask.RobotTask
import io.javalin.http.Context
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("com.seer.srd.huicang")

val customHttpResDecorator: HttpResponseDecorator = { _: RobotTask, ctx: Context, e: Exception? ->
    if (e == null) {
        ctx.json(mapOf("success" to true, "message" to "null"))
        ctx.status(201)
    } else {
        LOG.error("request failed from[${ctx.req.remoteAddr}]: $e")
        ctx.json(mapOf("success" to false, "message" to e.toString()))
        ctx.status(400)
    }
}
