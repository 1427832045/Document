package com.seer.srd.huaxin.hb

import com.seer.srd.Application
import com.seer.srd.BusinessError
import com.seer.srd.eventbus.EventBus
import com.seer.srd.http.Handlers
import com.seer.srd.http.HttpRequestMapping
import com.seer.srd.http.HttpServer.handle
import com.seer.srd.http.ReqMeta
import com.seer.srd.huaxin.hb.CustomComponent.extraComponents
import com.seer.srd.huaxin.hb.Services.checkFromMap
import com.seer.srd.huaxin.hb.Services.checkToMap
import com.seer.srd.huaxin.hb.Services.getLocationById
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.route.service.VehicleService
import com.seer.srd.setVersion
import io.javalin.http.Context
import io.javalin.http.HandlerType
import org.slf4j.LoggerFactory

fun main() {
    setVersion("华信", "1.0.1")

    Application.initialize()

    HuaXinHbApp.init()

    EventBus.robotTaskFinishedEventBus.add(Services::onRobotTaskFinished)
    EventBus.robotTaskUpdatedEventBus.add(Services::onRobotTaskUpdated)

    Application.start()
}

object HuaXinHbApp {

    private val logger = LoggerFactory.getLogger(HuaXinHbApp::class.java)

    fun init() {

        registerRobotTaskComponents(extraComponents)

        handle(
            HttpRequestMapping(HandlerType.POST, "huaxin/hb/go/:name", ::go, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.GET, "huaxin/hb/go/:name", ::getGo, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.POST, "huaxin/hb/end/:name", ::end, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.GET, "huaxin/hb/end/:name", ::getEnd, ReqMeta(auth = false, test = true))
        )

        val mockHandler = Handlers("seer")
        mockHandler.post("outstockbegin", ::mockReady, ReqMeta(auth = false, test = true))
        mockHandler.post("instockbegin", ::mockReady, ReqMeta(auth = false, test = true))
        mockHandler.post("outstockend", ::mockAgvLoaded, ReqMeta(auth = false, test = true))
    }

    private fun mockReady(ctx: Context) {
//        val location = ctx.bodyAsClass(MesReq::class.java).station
        val station = ctx.queryParam("station") as String
        val location = getLocationById(station)
        logger.debug("上位机收到${location}已经就位")
        ctx.status(200)
        ctx.json(mapOf("code" to 200, "message" to ""))
    }

    private fun mockAgvLoaded(ctx: Context) {
//        val location = ctx.bodyAsClass(MesReq::class.java).station
        val station = ctx.queryParam("station") as String
        val location = getLocationById(station)
        logger.debug("上位机收到${location}已经装载/卸载")
        ctx.status(200)
      ctx.json(mapOf("code" to 200, "message" to ""))
    }

    private fun go(ctx: Context) {
        val name = ctx.pathParam("name")
        val v = VehicleService.listVehicles().findLast { it.name == name } ?: throw BusinessError("$name 不存在!!")
        Services.letItGo(v)
        ctx.status(200)
    }

    private fun getGo(ctx: Context) {
        val name = ctx.pathParam("name")
        ctx.json(mapOf("go" to checkFromMap[name]))
        ctx.status(200)
    }

    private fun end(ctx: Context) {
        val name = ctx.pathParam("name")
        val v = VehicleService.listVehicles().findLast { it.name == name } ?: throw BusinessError("$name 不存在!!")
        Services.endItUp(v)
        ctx.status(200)
    }

    private fun getEnd(ctx: Context) {
        val name = ctx.pathParam("name")
        ctx.json(mapOf("end" to checkToMap[name]))
        ctx.status(200)
    }
}
