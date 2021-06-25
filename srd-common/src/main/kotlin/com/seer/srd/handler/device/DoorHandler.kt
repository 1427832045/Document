package com.seer.srd.handler.device

import com.seer.srd.Error400
import com.seer.srd.device.DoorService
import io.javalin.http.Context
import org.slf4j.LoggerFactory

object DoorHandler {

    private val logger = LoggerFactory.getLogger(DoorHandler::class.java)

    fun handleControlDoor(ctx: Context) {
        val doorName = ctx.pathParam("name")
        val manager = DoorService.getDoorByName(doorName)
        val manualOpt = ctx.queryParam("optMode") ?: "-1"
        when (val action = ctx.queryParam("action")) {
            "enable", "disable", "stop" -> logger.warn("$action not support")
            "open" -> manager.open("")
            "close" -> manager.close("")
            "byPass" -> manager.byPass(manualOpt.toInt(), "")
            else -> throw Error400("UnsupportedAction", "Unsupported action: $action")
        }
        ctx.header("Access-Control-Allow-Origin", "*")
        ctx.status(200)
    }

    fun handleListDoors(ctx: Context) {
        val doors = DoorService.listStatusDO()
        ctx.header("Access-Control-Allow-Origin", "*")
        ctx.json(doors)
    }

    fun handleGetDoor(ctx: Context) {
        val name = ctx.pathParam("name")
        val manager = DoorService.getDoorByName(name)
        val status = manager.getDoorStatusDO()
        ctx.header("Access-Control-Allow-Origin", "*")
        ctx.json(status)
    }

}