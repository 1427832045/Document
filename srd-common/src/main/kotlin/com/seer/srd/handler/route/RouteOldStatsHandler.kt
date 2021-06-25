package com.seer.srd.handler.route

import com.seer.srd.http.getPageNo
import com.seer.srd.http.getPageSize
import com.seer.srd.route.RouteStatsQuery
import com.seer.srd.route.getRouteStats
import io.javalin.http.Context
import java.time.Instant

fun handleGetRouteStats(ctx: Context) {
    val startTime = ctx.queryParam("startTime")
    val query = RouteStatsQuery(
        getPageNo(ctx), getPageSize(ctx),
        if (startTime == null) null else Instant.parse(startTime),
        ctx.queryParam("event"), ctx.queryParam("label")
    )
    val r = getRouteStats(query)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.json(r)
}