package com.seer.srd.handler

import com.seer.srd.http.getReqLang
import com.seer.srd.robottask.*
import com.seer.srd.util.mapper
import io.javalin.http.Context
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.seer.srd.handler")

fun handleGetManualTasks(ctx: Context) {
    val username = ctx.queryParam("username")
    if (!username.isNullOrBlank()) {
        ctx.json(listManualTasksByUsername(username))
    } else
    ctx.json(listManualTasks())
}

fun handleUpdateManualTask(ctx: Context) {
    val bodyString = ctx.body()
    logger.info("ManualTask body: $bodyString")
    val req = mapper.readValue(bodyString, ManualTask::class.java)
    updateManualTask(req, getReqLang(ctx))
    ctx.status(204)
}

fun handleCreateManualTask(ctx: Context) {
    val bodyString = ctx.body()
    logger.info("ManualTask body: $bodyString")
    val req = mapper.readValue(bodyString, ManualTask::class.java)
    updateManualTask(req, getReqLang(ctx))
    ctx.status(204)
}

fun handleRemoveManualTask(ctx: Context) {
    removeManualTask(ObjectId(ctx.pathParam("id")))
    ctx.status(204)
}

fun handleExecuteManualTask(ctx: Context) {
    executeManualTask(ObjectId(ctx.pathParam("id")))
    ctx.status(201)
}

fun handleExecuteTempManualTask(ctx: Context) {
    val bodyString = ctx.body()
    logger.info("ManualTask body: $bodyString")
    val req = mapper.readValue(bodyString, ManualTask::class.java)
    executeTempManualTask(req)
    ctx.status(201)
}
