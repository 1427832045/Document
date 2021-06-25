package com.seer.srd.handler.route

import com.seer.srd.http.getPageNo
import com.seer.srd.http.getPageSize
import com.seer.srd.route.service.CreateOrderSequenceReq
import com.seer.srd.route.service.ListOrderSequencesQuery
import com.seer.srd.route.service.OrderSequenceIOService.createOrderSequence
import com.seer.srd.route.service.OrderSequenceIOService.getOrderSequenceOutputByName
import com.seer.srd.route.service.OrderSequenceIOService.listOrderSequenceOutputs
import com.seer.srd.route.service.OrderSequenceIOService.markOrderSequenceComplete
import com.seer.srd.route.service.OrderSequenceIOService.withdrawalSequenceByName
import com.seer.srd.util.mapper
import io.javalin.http.Context
import org.opentcs.data.ObjectUnknownException
import org.slf4j.LoggerFactory
import java.lang.IllegalArgumentException

private val logger = LoggerFactory.getLogger("com.seer.srd.handler.route")

fun handleCreateOrderSequence(ctx: Context) {
    val name = ctx.pathParam("name")
    val bodyString = ctx.body()
    logger.info("Create OrderSequence body: $bodyString")
    val req: CreateOrderSequenceReq
    try {
        req = mapper.readValue(bodyString, CreateOrderSequenceReq::class.java)
    } catch (e: Exception) {
        throw IllegalArgumentException("Order sequence's body is illegal, ${e.message}")
    }
    createOrderSequence(name, req)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.status(200)
}

fun handleMarkOrderSequenceComplete(ctx: Context) {
    val name = ctx.pathParam("name")
    markOrderSequenceComplete(name)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.status(200)
}

fun handleWithdrawalSequenceByName(ctx: Context) {
    val name = ctx.pathParam("name")
    val immediate = ctx.queryParam("immediate")?.toBoolean() ?: false
    val disableVehicle = ctx.queryParam("disableVehicle")?.toBoolean() ?: false
    
    withdrawalSequenceByName(name, immediate, disableVehicle)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.status(200)
}

fun handleGetOrderSequenceByName(ctx: Context) {
    val name = ctx.pathParam("name")
    val seq = getOrderSequenceOutputByName(name) ?: throw ObjectUnknownException("Unknown order sequence: '$name'")
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.json(seq)
    ctx.status(200)
}

fun handleListOrderSequences(ctx: Context) {
    val completeStr = ctx.queryParam("complete")
    val finishedStr = ctx.queryParam("finished")
    val failureFatalStr = ctx.queryParam("failureFatal")
    val query = ListOrderSequencesQuery(
        getPageNo(ctx), getPageSize(ctx), ctx.queryParam("namePrefix"),
        if (completeStr == null) null else completeStr == "true",
        if (finishedStr == null) null else finishedStr == "true",
        if (failureFatalStr == null) null else failureFatalStr == "true",
        ctx.queryParam("orderNamePrefix"),
        ctx.queryParam("category"),
        ctx.queryParam("intendedVehicle"),
        ctx.queryParam("processingVehicle")
    )
    val r = listOrderSequenceOutputs(query)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.json(r)
    ctx.status(200)
}