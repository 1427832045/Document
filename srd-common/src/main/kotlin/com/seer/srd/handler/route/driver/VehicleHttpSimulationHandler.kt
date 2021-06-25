package com.seer.srd.handler.route.driver

import com.seer.srd.Error400
import com.seer.srd.vehicle.driver.io.http.simulations
import com.seer.srd.vehicle.driver.io.http.SendMovementCommandReq
import io.javalin.http.Context

fun handleMovementCommands(ctx: Context) {
    val vehicleName = ctx.pathParam("name")
    val req = ctx.bodyAsClass(SendMovementCommandReq::class.java)
    val sim = simulations[vehicleName] ?: throw Error400("UnknownVehicle", "Unknown vehicle $vehicleName")
    sim.acceptMovementCommands(req.commands)
    ctx.status(204)
}

fun handlePauseVehicle(ctx: Context) {
    val vehicleName = ctx.pathParam("name")
    val sim = simulations[vehicleName] ?: throw Error400("UnknownVehicle", "Unknown vehicle $vehicleName")
    sim.pauseVehicle()
    ctx.status(204)
}

fun handleResumeVehicle(ctx: Context) {
    val vehicleName = ctx.pathParam("name")
    val sim = simulations[vehicleName] ?: throw Error400("UnknownVehicle", "Unknown vehicle $vehicleName")
    sim.resumeVehicle()
    ctx.status(204)
}

fun handleClearAllMovementCommands(ctx: Context) {
    val vehicleName = ctx.pathParam("name")
    val sim = simulations[vehicleName] ?: throw Error400("UnknownVehicle", "Unknown vehicle $vehicleName")
    sim.clearAllMovementCommands()
    ctx.status(204)
}