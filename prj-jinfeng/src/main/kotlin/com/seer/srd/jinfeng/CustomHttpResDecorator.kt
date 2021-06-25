package com.seer.srd.jinfeng

import com.seer.srd.robottask.HttpResponseDecorator
import com.seer.srd.robottask.RobotTask
import io.javalin.http.Context
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("com.seer.srd.jinfeng")

val customHttpResDecorator: HttpResponseDecorator = { _: RobotTask, ctx: Context, e: Exception? ->
    if (e == null) {
        ctx.json(mapOf("status" to "0", "errorinfo" to null, "data" to null))
        ctx.status(201)
    } else {
        LOG.error("request failed from[${ctx.req.remoteAddr}]: $e", e)
        ctx.json(mapOf("status" to "1", "errorinfo" to e.message, "data" to null))
        ctx.status(200)
    }
}
