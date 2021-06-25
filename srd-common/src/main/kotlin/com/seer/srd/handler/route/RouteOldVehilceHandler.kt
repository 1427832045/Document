package com.seer.srd.handler.route

import com.seer.srd.Error400
import com.seer.srd.route.getVehicleDetails
import com.seer.srd.route.getVehicleDetailsByName
import com.seer.srd.route.service.VehicleService
import com.seer.srd.util.splitTrim
import com.seer.srd.vehicle.VehicleManager.pauseAllVehicles
import com.seer.srd.vehicle.VehicleManager.pauseVehicleByName
import com.seer.srd.vehicle.VehicleManager.setAllVehiclesIntegrationLevel
import com.seer.srd.vehicle.VehicleManager.setVehicleEnergyLevelCritical
import com.seer.srd.vehicle.VehicleManager.setVehicleEnergyLevelFullyRecharged
import com.seer.srd.vehicle.VehicleManager.setVehicleEnergyLevelGood
import com.seer.srd.vehicle.VehicleManager.setVehicleEnergyLevelSufficientlyRecharged
import com.seer.srd.vehicle.VehicleManager.setVehicleIntegrationLevel
import com.seer.srd.vehicle.VehicleManager.setVehicleProcessableCategories
import com.seer.srd.vehicle.VehicleManager.withdrawByVehicle
import com.seer.srd.vehicle.VehicleOutput
import io.javalin.http.Context
import org.opentcs.data.ObjectUnknownException
import java.lang.IllegalArgumentException

fun handleGetVehicles(ctx: Context) {
    val procState = ctx.queryParam("procState")

    var vehicles = VehicleService.listVehiclesOutputs()
    if (!procState.isNullOrBlank()) vehicles = vehicles.filter { it.procState.name == procState }
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.json(vehicles)
    ctx.status(200)
}

fun handleGetVehicleStateByName(ctx: Context) {
    val name = ctx.pathParam("name")
    if (name.isBlank()) throw IllegalArgumentException("Vehicle name is missing.")
    ctx.header("Access-Control-Allow-Origin", "*")
    val v = VehicleService.getVehicleOrNull(name)
    if (v == null) {
        throw ObjectUnknownException("Unknown vehicle '$name'.")
    } else {
        ctx.json(VehicleOutput(v))
        ctx.status(200)
    }
}

fun handleGetVehicleDetails(ctx: Context) {
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.json(getVehicleDetails())
    ctx.status(200)
}

fun handleGetVehicleDetailsByName(ctx: Context) {
    val name = ctx.pathParam("name")
    val detail = getVehicleDetailsByName(name) ?: throw ObjectUnknownException("Unknown vehicle '$name'.")
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.json(detail ?: emptyMap<String, Any>())
    ctx.status(200)
}

fun handlePauseAllVehicles(ctx: Context) {
    val value = ctx.queryParam("newValue")
    if (value.isNullOrBlank()) {
        throw IllegalArgumentException("Param 'newValue' is missing.")
    }
    val lowerValue = value.toLowerCase()
    if (lowerValue != "true" && lowerValue != "false") {
        throw IllegalArgumentException("Param 'newValue' is illegal: $value.")
    }
    val flag = lowerValue == "true"
    pauseAllVehicles(flag)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.header("Access-Control-Allow-Methods", "POST, GET, PUT, OPTIONS, DELETE")
    ctx.status(200)
}

fun handlePauseVehicleByName(ctx: Context) {
    val value = ctx.queryParam("newValue")
    if (value.isNullOrBlank()) {
        throw IllegalArgumentException("Param 'newValue' is missing.")
    }
    val lowerValue = value.toLowerCase()
    if (lowerValue != "true" && lowerValue != "false") {
        throw IllegalArgumentException("Param 'newValue' is illegal: $value.")
    }
    val flag = lowerValue == "true"
    val name = ctx.pathParam("name")
    pauseVehicleByName(name, flag)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.header("Access-Control-Allow-Methods", "POST, GET, PUT, OPTIONS, DELETE")
    ctx.status(200)
}

fun handleWithdrawalByVehicle(ctx: Context) {
    val immediate = ctx.queryParam("immediate")?.toBoolean() ?: false
    val disableVehicle = ctx.queryParam("disableVehicle")?.toBoolean() ?: false
    val name = ctx.pathParam("name")

    withdrawByVehicle(name, immediate, disableVehicle)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.status(200)
}

fun handleSetAllVehiclesIntegrationLevel(ctx: Context) {
    val flag = ctx.queryParam("newValue")
        ?: throw IllegalArgumentException("Missing query parameter: newValue.")
    setAllVehiclesIntegrationLevel(flag)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.header("Access-Control-Allow-Methods", "POST, GET, PUT, OPTIONS, DELETE")
    ctx.status(200)
}

fun handleSetVehicleIntegrationLevel(ctx: Context) {
    val flag = ctx.queryParam("newValue")
        ?: throw IllegalArgumentException("Missing query parameter: newValue.")
    val name = ctx.pathParam("name")
    setVehicleIntegrationLevel(name, flag)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.header("Access-Control-Allow-Methods", "POST, GET, PUT, OPTIONS, PATCH, DELETE")
    ctx.status(200)
}

fun handleSetProcessableCategories(ctx: Context) {
    val name = ctx.pathParam("name")
    val newValues = ctx.queryParam("newValues")
        ?: throw IllegalArgumentException("Missing query parameter: newValues.")
    val categories = splitTrim(newValues, ",")
    setVehicleProcessableCategories(name, categories)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.header("Access-Control-Allow-Methods", "POST, GET, PUT, OPTIONS, DELETE")
    ctx.status(200)
}

fun handleSetEnergyLevelGood(ctx: Context) {
    val level = ctx.queryParam("newValue")?.toInt()?.coerceIn(0, 100)
        ?: throw Error400("BadEnergyLevel", "Bad energy level")
    setVehicleEnergyLevelGood(ctx.pathParam("name"), level)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.header("Access-Control-Allow-Methods", "POST, GET, PUT, OPTIONS, DELETE")
    ctx.status(200)
}

fun handleSetEnergyLevelCritical(ctx: Context) {
    val level = ctx.queryParam("newValue")?.toInt()?.coerceIn(0, 100)
        ?: throw Error400("BadEnergyLevel", "Bad energy level")
    setVehicleEnergyLevelCritical(ctx.pathParam("name"), level)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.header("Access-Control-Allow-Methods", "POST, GET, PUT, OPTIONS, DELETE")
    ctx.status(200)
}

fun handleSetEnergyLevelFRC(ctx: Context) {
    val level = ctx.queryParam("newValue")?.toInt()?.coerceIn(0, 100)
        ?: throw Error400("BadEnergyLevel", "Bad energy level")
    setVehicleEnergyLevelFullyRecharged(ctx.pathParam("name"), level)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.header("Access-Control-Allow-Methods", "POST, GET, PUT, OPTIONS, DELETE")
    ctx.status(200)
}

fun handleSetEnergyLevelSRC(ctx: Context) {
    val level = ctx.queryParam("newValue")?.toInt()?.coerceIn(0, 100)
        ?: throw Error400("BadEnergyLevel", "Bad energy level")
    setVehicleEnergyLevelSufficientlyRecharged(ctx.pathParam("name"), level)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.header("Access-Control-Allow-Methods", "POST, GET, PUT, OPTIONS, DELETE")
    ctx.status(200)
}