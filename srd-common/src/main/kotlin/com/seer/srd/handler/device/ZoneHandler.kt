package com.seer.srd.handler.device

import com.seer.srd.Error400
import com.seer.srd.device.ZoneService
import io.javalin.http.Context
import org.slf4j.LoggerFactory

object ZoneHandler {

    private val logger = LoggerFactory.getLogger(ZoneHandler::class.java)

    fun handleControlZone(ctx: Context) {
        val zoneName = ctx.pathParam("name")
        val manager = ZoneService.getZoneByName(zoneName)
        val systemId = ZoneService.parseSystemIdByIp(ctx.queryParam("systemId"), ctx.req.remoteAddr)
        val robot = ctx.queryParam("vehicle")
        // val remark = ctx.queryParam("remark")
        when (val action = ctx.queryParam("action")) {
            "enable", "disable" -> logger.warn("No support to enable/disable zone")
            "enter" -> {
                if (robot.isNullOrBlank() || zoneName.isBlank())
                    throw Error400("BadRequest", "请求参数有误vehicle:[${robot}]，mutexZone[${zoneName}]!!!")
                manager.enter(robot, systemId, zoneName)
            }
            "leave" -> {
                if (robot.isNullOrBlank() || zoneName.isBlank())
                    throw Error400("BadRequest", "请求参数有误vehicle:[${robot}]，mutexZone[${zoneName}]!!!")
                manager.leave(robot, systemId, zoneName)
            }
            "kickOne" -> {
                if (robot.isNullOrBlank() || zoneName.isBlank())
                    throw Error400("BadRequest", "请求参数有误vehicle:[${robot}]，mutexZone[${zoneName}]!!!")
                manager.kickRobot(robot, systemId)
            }
            "kickAll" -> {
                if (zoneName.isBlank())
                    throw Error400("BadRequest", "请求参数有误 mutexZone[${zoneName}]!!!")
                manager.kickAllRobots(systemId)
            }
            else ->
                throw Error400("UnsupportedAction", "Unsupported action: $action")
        }
        ctx.header("Access-Control-Allow-Origin", "*")
        ctx.status(200)
    }

    fun handleGetAllStatus(ctx: Context) {
        val systemId = ZoneService.parseSystemIdByIp(ctx.queryParam("systemId"), ctx.req.remoteAddr)
        val zones = ZoneService.listZones()
        val statuses = zones.map { it.getStatusDO(systemId) }
        ctx.header("Access-Control-Allow-Origin", "*")
        ctx.json(statuses)
    }

    fun handleGetOneStatus(ctx: Context) {
        val zoneName = ctx.pathParam("name")
        val systemId = ZoneService.parseSystemIdByIp(ctx.queryParam("systemId"), ctx.req.remoteAddr)
        val manager = ZoneService.getZoneByName(zoneName)
        ctx.header("Access-Control-Allow-Origin", "*")
        ctx.json(manager.getStatusDO(systemId))
    }

    fun handleGetAllDetails(ctx: Context) {
        val zones = ZoneService.listZones()
        val moreStatuses = zones.map { it.getZoneMoreStatusDO() }
        ctx.header("Access-Control-Allow-Origin", "*")
        ctx.json(moreStatuses)
    }
    
}