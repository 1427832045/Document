package com.seer.srd.handler.route

import com.fasterxml.jackson.databind.node.ObjectNode
import com.seer.srd.http.getPageNo
import com.seer.srd.http.getPageSize
import com.seer.srd.route.service.CreateTransportOrderReq
import com.seer.srd.route.service.ListTransportOrdersQuery
import com.seer.srd.route.service.TransportOrderIOService.changeOrderDeadline
import com.seer.srd.route.service.TransportOrderIOService.createTransportOrder
import com.seer.srd.route.service.TransportOrderIOService.getTransportOrderOutputByName
import com.seer.srd.route.service.TransportOrderIOService.listTransportOrderOutputs
import com.seer.srd.route.service.TransportOrderIOService.withdrawTransportOrder
import com.seer.srd.util.mapper
import com.seer.srd.util.splitTrim
import io.javalin.http.Context
import org.slf4j.LoggerFactory
import java.lang.IllegalArgumentException
import java.time.Instant
import java.time.format.DateTimeParseException

private val logger = LoggerFactory.getLogger("com.seer.srd.handler.route")

fun handleListTransportOrders(ctx: Context) {
    val pageNo = getPageNo(ctx)
    val pageSize = getPageSize(ctx)
    
    val intendedVehicle = ctx.queryParam("intendedVehicle")
    val processingVehicle = ctx.queryParam("processingVehicle")
    val category = ctx.queryParam("category")
    val states = splitTrim(ctx.queryParam("state"), ",")
    val regexp = ctx.queryParam("regexp")
    
    val query = ListTransportOrdersQuery(pageNo, pageSize, intendedVehicle, processingVehicle, category, states, regexp)
    val r = listTransportOrderOutputs(query)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.json(r)
    ctx.status(200)
}

fun handleGetTransportOrderByName(ctx: Context) {
    val name = ctx.pathParam("name")
    val order = getTransportOrderOutputByName(name)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.json(order!!)
    ctx.status(200)
}

fun handleCreateTransportOrder(ctx: Context) {
    val name = ctx.pathParam("name")
    val req: CreateTransportOrderReq
    // 转换 body，如果 properties 的 value 是一个对象，那么把它转换成 string
    try {
        val bodyString = ctx.body()
        logger.info("Create TransportOrder body: $bodyString")
        val json = mapper.readTree(bodyString)
        json["destinations"].elements().forEach { node ->
            node["properties"].elements().forEach {
                if (it.get("value") != null && it.get("value").isContainerNode) {
                    val objectNode: ObjectNode = it as ObjectNode
                    objectNode.put("value", it.get("value").toString())
                }
            }
        }
        req = mapper.treeToValue(json, CreateTransportOrderReq::class.java)
    } catch (e: Exception) {
        throw IllegalArgumentException("Transport order body is illegal, ${e.message}", e)
    }
    createTransportOrder(name, req)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.status(200)
}

fun handleWithdrawTransportOrder(ctx: Context) {
    val immediate = ctx.queryParam("immediate")?.toBoolean() ?: false
    val disableVehicle = ctx.queryParam("disableVehicle")?.toBoolean() ?: false
    val name = ctx.pathParam("name")
    
    withdrawTransportOrder(name, immediate, disableVehicle)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.status(200)
}

fun handleChangeOrderDeadline(ctx: Context) {
    val name = ctx.pathParam("name")
    val deadline: Instant
    try {
        deadline = Instant.parse(ctx.queryParam("newValue"))
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Illegal time expression ${e.message}")
    }
    changeOrderDeadline(name, deadline)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.header("Access-Control-Allow-Methods", "POST, GET, PUT, OPTIONS, DELETE")
    ctx.status(200)
}

fun handleListHistoryTransportOrders(ctx: Context) {
    val query = ListTransportOrdersQuery(
        getPageNo(ctx), getPageSize(ctx), ctx.queryParam("intendedVehicle"), ctx.queryParam("processingVehicle"),
        ctx.queryParam("category"), splitTrim(ctx.queryParam("state"), ","), ctx.queryParam("prefix")
    )
    val r = listTransportOrderOutputs(query)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.json(r)
    ctx.status(200)
}