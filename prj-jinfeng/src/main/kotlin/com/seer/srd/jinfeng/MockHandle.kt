package com.seer.srd.jinfeng

import com.seer.srd.http.Handlers
import com.seer.srd.http.ReqMeta
import com.seer.srd.jinfeng.ExtraComponents.tellErp
import io.javalin.http.Context
import org.slf4j.LoggerFactory
import kotlin.random.Random


object MockHandle {
    private val logger = LoggerFactory.getLogger(MockHandle::class.java)

    fun mockTellErp(ctx: Context) {
        val body = ctx.body()
        logger.info("====================mock tell erp param is : $body")
        val pass = Random.nextBoolean()
        logger.info("====================Mock erp: $body $pass")
        ctx.json(mapOf("Status" to 0, "ErrorInfo" to "success", "Data" to null))
        ctx.status(200)
    }

    fun testTellErp(ctx: Context) {
        tellErp("aaa", "bbb", null)
        ctx.json(mapOf("pass" to "success"))
    }


}