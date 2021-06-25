package com.seer.srd.handler.route.driver

import com.seer.srd.vehicle.driver.VehicleDriverManager.getVehicleCommAdapterOrNull
import com.seer.srd.vehicle.driver.io.VehicleStatus
import com.seer.srd.vehicle.driver.io.http.updateVehicleEndpoint
import io.javalin.http.Context
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("com.seer.srd.handler.route.driver")

class VehicleSignIn(
    var endpoint: String = ""
)

fun handleVehicleSignIn(ctx: Context) {
    val name = ctx.pathParam("name")
    val req = ctx.bodyAsClass(VehicleSignIn::class.java)
    LOG.info("vehicle sign in $name ${req.endpoint}")
    updateVehicleEndpoint(name, req.endpoint)
}

fun handleVehicleReportStatus(ctx: Context) {
    val name = ctx.pathParam("name")
    val adapter = getVehicleCommAdapterOrNull(name)
    if (adapter != null) {
        val req = ctx.bodyAsClass(VehicleStatus::class.java)
        adapter.onVehicleStatus(req)
    }
    ctx.status(204)
}

fun handleVehicleReportDetails(ctx: Context) {
    val name = ctx.pathParam("name")
    val adapter = getVehicleCommAdapterOrNull(name)
    if (adapter != null) {
        val req = ctx.body()
        adapter.onVehicleDetails(req)
    }
    ctx.status(204)
}