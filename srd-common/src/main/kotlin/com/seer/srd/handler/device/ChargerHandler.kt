package com.seer.srd.handler.device

import com.seer.srd.Error400
import com.seer.srd.device.charger.AbstractChargerManager
import com.seer.srd.device.charger.ChargerManager
import com.seer.srd.device.charger.ChargerService
import com.seer.srd.device.charger.RepBody
import com.seer.srd.util.mapper
import io.javalin.http.Context
import org.slf4j.LoggerFactory

object ChargerHandler {

    private val logger = LoggerFactory.getLogger(ChargerHandler::class.java)

    fun handleControlCharger(ctx: Context) {
        val name = ctx.pathParam("name")

        val action = ctx.queryParam("action")
            ?: throw Error400("InvalidAction", "there is no action in request.")
        val remark = ctx.queryParam("remark") ?: "from api ${ctx.req.remoteAddr}"
        val manager = ChargerService.getChargerByName(name)

        when (action) {
            "enter" -> {
                manager.turnOn(true, remark)
                ctx.json(getRepBody(true, name, manager))
            }
            "leave" -> {
                manager.turnOn(false, remark)
                ctx.json(getRepBody(false, name, manager))
            }
            "reconnect" -> {
                manager.tryReconnect()
            }
            "enable", "disable", "stop", "setMaxCurrent", "setMaxVoltage", "update", "remove", "test" ->
                logger.warn("$action not support")

            else -> throw Error400("UnsupportedAction", "Unsupported action: $action")
        }
        ctx.header("Access-Control-Allow-Origin", "*")
        ctx.status(200)
    }

    /** 根据请求获取响应的包体 */
    fun getRepBody(turnOn: Boolean, deviceName: String, charger: AbstractChargerManager): RepBody {
        val chargerCharging = charger.isWorking()
        val vehicleCharging = charger.isVehicleCharging()
        val condition = if (turnOn) (chargerCharging && vehicleCharging) else (!chargerCharging && !vehicleCharging)
        val lastActionStatus = if (condition) "DONE" else "EXECUTING"
        val lastAction = if (turnOn) "enter" else "leave"
        val chargerStatus = if (charger.chargerHasError().result) "ERROR" else "IDLE"
        val name = charger.model.config.name
        logger.debug("charger[$name]: chargerCharging:$chargerCharging, vehicleCharging:$vehicleCharging, condition: $condition")
        logger.debug("charger[$name]: deviceName: $deviceName, lastActionStatus: $lastActionStatus, lastAction: $lastAction, chargerStatus: $chargerStatus")
        return RepBody(lastActionStatus, deviceName, lastAction, chargerStatus)
    }

    /** 获取所有充电机信息 */
    fun handleListChargers(ctx: Context) {
        ctx.header("Access-Control-Allow-Origin", "*")
        ctx.json(ChargerService.listChargerStatus())
    }

    /** 获取指定的充电机信息 */
    fun handleGetChargerStatus(ctx: Context) {
        val name = ctx.pathParam("name")
        ctx.header("Access-Control-Allow-Origin", "*")
        ctx.json(ChargerService.getChargerStatusByName(name))
    }

    fun handlerCancelChargeByOrder(ctx: Context) {
        val body = mapper.readTree(ctx.body())
        val orderNames = body.toList()
        for (orderName in orderNames) {
            ChargerService.cancelChargeByOrder(orderName.asText())
        }
    }

    fun handlerCancelChargeByVehicle(ctx: Context) {
        val body = mapper.readTree(ctx.body())
        val vehicleIds = body.toList()
        for (vehicleId in vehicleIds) {
            ChargerService.cancelChargeByVehicle(vehicleId.asText())
        }
    }

}
