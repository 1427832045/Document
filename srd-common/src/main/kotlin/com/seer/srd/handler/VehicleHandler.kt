package com.seer.srd.handler

import com.seer.srd.Error400
import com.seer.srd.Error404
import com.seer.srd.route.*
import com.seer.srd.route.service.VehicleService
import com.seer.srd.util.mapper
import com.seer.srd.util.splitTrim
import com.seer.srd.vehicle.*
import com.seer.srd.vehicle.VehicleManager.enableAdapter
import com.seer.srd.vehicle.VehicleManager.disableAdapter
import com.seer.srd.vehicle.VehicleManager.acquireVehicle
import com.seer.srd.vehicle.VehicleManager.clearVehicleErrors
import com.seer.srd.vehicle.VehicleManager.disownVehicle
import com.seer.srd.vehicle.VehicleManager.pauseAllVehicles
import com.seer.srd.vehicle.VehicleManager.setAllVehiclesIntegrationLevel
import com.seer.srd.vehicle.VehicleManager.setVehicleIntegrationLevel
import com.seer.srd.vehicle.VehicleManager.setVehicleProcessableCategories
import com.seer.srd.vehicle.VehicleManager.setVehicleProperties
import com.seer.srd.vehicle.VehicleManager.withdrawByVehicle
import io.javalin.http.Context
import org.slf4j.LoggerFactory

object VehicleHandler {
    private val logger = LoggerFactory.getLogger(VehicleHandler::class.java)
    fun handleListVehicles(ctx: Context) {
        ctx.json(VehicleService.listVehiclesOutputs())
    }
    
    fun handleListVehiclesDetails(ctx: Context) {
        ctx.json(getVehicleDetails())
    }
    
    fun handleGetVehicleDetails(ctx: Context) {
        val vehicleName = ctx.pathParam("vehicle")
        val details = getVehicleDetails()[vehicleName]
        if (details == null) ctx.json(mapOf("code" to "NoSuchVehicleDetails", "message" to vehicleName))
        else ctx.json(details)
    }

    fun handleEnableAdapter(ctx: Context) {
        val name = ctx.pathParam("name")
        enableAdapter(name)
        ctx.status(204)
    }

    fun handleEnableManyAdapters(ctx: Context) {
        val req = mapper.readTree(ctx.body())
        val vehicles = req["vehicles"].map { it.asText().trim() }
        logger.debug("Enable vehicle adapters: $vehicles")

        vehicles.forEach {
            try {
                enableAdapter(it)
            } catch (e: Exception) {
                logger.error("Enable adapter of vehicle[$it] occurred error after enabled !", e)
            }
        }

        ctx.status(204)
    }

    fun handleDisableAdapter(ctx: Context) {
        val name = ctx.pathParam("name")
        disableAdapter(name)
        ctx.status(204)
    }

    fun handleDisableManyAdapters(ctx: Context) {
        val req = mapper.readTree(ctx.body())
        val vehicles = req["vehicles"].map { it.asText().trim() }
        logger.debug("Disable vehicle adapters: $vehicles")

        vehicles.forEach {
            try {
                disableAdapter(it)
            } catch (e: Exception) {
                logger.error("Disable adapter of vehicle[$it] occurred error after disabled !", e)
            }
        }

        ctx.status(204)
    }

    fun handlePauseAll(ctx: Context) {
        val flag = ctx.pathParam("flag").toBoolean()

        pauseAllVehicles(flag)
        
        ctx.status(204)
    }

    fun handleAcquireVehicle(ctx: Context) {
        val name = ctx.pathParam("name")
        val success = acquireVehicle(name)
        ctx.json(success)
    }

    fun handleAcquireMany(ctx: Context) {
        val req = mapper.readTree(ctx.body())
        val vehicles = req["vehicles"].map { it.asText().trim() }
        logger.debug("Acquire vehicles: $vehicles")

        vehicles.forEach { acquireVehicle(it) }

        ctx.status(204)
    }

    fun handleDisownVehicle(ctx: Context) {
        val name = ctx.pathParam("name")
        val success = disownVehicle(name)
        ctx.json(success)
    }

    fun handleDisownMany(ctx: Context) {
        val req = mapper.readTree(ctx.body())
        val vehicles = req["vehicles"].map { it.asText().trim() }
        logger.debug("Disown vehicles: $vehicles")

        vehicles.forEach { disownVehicle(it) }

        ctx.status(204)
    }

    fun handleClearVehicleErrors(ctx: Context) {
        val name = ctx.pathParam("name")
        val success = clearVehicleErrors(name)
        ctx.json(success)
    }

    fun handleClearManyErrors(ctx: Context) {
        val req = mapper.readTree(ctx.body())
        val vehicles = req["vehicles"].map { it.asText().trim() }
        logger.debug("Clear errors: $vehicles")
        vehicles.forEach { clearVehicleErrors(it) }

        ctx.status(204)
    }

    fun handleChangeAllIntegrationLevel(ctx: Context) {
        val level = ctx.pathParam("level")
        
        setAllVehiclesIntegrationLevel(level)
        
        ctx.status(204)
    }
    
    fun handleChangeIntegrationLevel(ctx: Context) {
        val req = ctx.bodyAsClass(ChangeIntegrationLevel::class.java)
        for (vehicle in req.vehicles) {
            try {
                setVehicleIntegrationLevel(vehicle, req.level)
            } catch (ex: Exception) {
                logger.error("Change $vehicle to ${req.level} failed, $ex", ex)
            }
        }
        ctx.status(204)
    }
    
    fun handleWithdrawal(ctx: Context) {
        val req = ctx.bodyAsClass(Withdrawal::class.java)
        
        val disableVehicle = req.disableVehicle.isNotBlank() && req.disableVehicle == "true"
        val immediate = req.immediate.isNotBlank() && req.immediate == "true"
        
        for (vehicle in req.vehicles) {
            try {
                withdrawByVehicle(vehicle, immediate, disableVehicle)
            } catch (ex: Exception) {
                logger.error("Withdraw $vehicle order failed, $ex", ex)
            }
        }
        
        ctx.status(204)
    }
    
    fun handleChangeCategories(ctx: Context) {
        val req = ctx.bodyAsClass(ChangeProcessableCategories::class.java)
        val categories = splitTrim(req.categories, ",")
        for (vehicle in req.vehicles) {
            try {
                setVehicleProcessableCategories(vehicle, categories)
            } catch (ex: Exception) {
                logger.error("Set $vehicle processable categories to ${req.categories} failed, $ex", ex)
            }
        }
        ctx.status(204)
    }

    fun handleSetVehicleProperties(ctx: Context) {
        val req = ctx.bodyAsClass(SetVehicleProperties::class.java)
        for (vehicle in req.vehicles) {
            try {
                setVehicleProperties(vehicle, req.properties)
            } catch (ex: Exception) {
                logger.error("Set $vehicle properties to ${req.properties} failed, $ex", ex)
            }
        }
        ctx.status(204)
    }
    
    fun handleListVehicleChannels(ctx: Context) {
        val channels = VehicleWebSocketManager.listVehicleChannels()
        ctx.json(channels)
    }
    
}

class ChangeIntegrationLevel(
    var vehicles: List<String> = emptyList(),
    var level: String = ""
)

class Withdrawal(
    var vehicles: List<String> = emptyList(),
    var disableVehicle: String = "",
    var immediate: String = ""
)

class ChangeProcessableCategories(
    var vehicles: List<String> = emptyList(),
    var categories: String = ""
)

class SetVehicleProperties(
    var vehicles: List<String> = emptyList(),
    var properties: Map<String, String>
)