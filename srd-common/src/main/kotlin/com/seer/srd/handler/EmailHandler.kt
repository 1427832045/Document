package com.seer.srd.handler

import com.seer.srd.email.sendMail
import io.javalin.http.Context
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.seer.srd.handler")

fun handleSendMail(ctx: Context) {
    val req = ctx.bodyAsClass(EmailData::class.java)
    sendMail(req.title.trim(), req.content.trim(), req.recipients?.map { it.trim() })
    ctx.status(200)
}

class EmailData(
        var title: String = "",
        var content: String = "",
        var recipients: List<String>? = null
)