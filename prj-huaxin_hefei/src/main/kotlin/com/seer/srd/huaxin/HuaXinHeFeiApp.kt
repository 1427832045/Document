package com.seer.srd.huaxin

import com.seer.srd.Application
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.eventbus.EventBus
import com.seer.srd.http.HttpRequestMapping
import com.seer.srd.http.HttpServer.handle
import com.seer.srd.http.ReqMeta
import com.seer.srd.huaxin.CustomComponent.extraComponents
import com.seer.srd.huaxin.Services.checkFromMap
import com.seer.srd.huaxin.Services.checkToMap
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.route.service.VehicleService
import com.seer.srd.setVersion
import io.javalin.http.Context
import io.javalin.http.HandlerType
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*

fun main() {
    setVersion("l2", "3.0.16")

    Application.initialize()

    HuaXinHeFeiApp.init()

    EventBus.robotTaskFinishedEventBus.add(Services::onRobotTaskFinished)
    EventBus.robotTaskUpdatedEventBus.add(Services::onRobotTaskUpdated)

    Application.start()
}

object HuaXinHeFeiApp {

    fun init() {

        registerRobotTaskComponents(extraComponents)

        handle(
            HttpRequestMapping(HandlerType.POST, "hefei/go/:name", ::go, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.GET, "hefei/go/:name", ::getGo, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.POST, "hefei/end/:name", ::end, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.GET, "hefei/end/:name", ::getEnd, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.GET, "hefei/taskDefConfig", ::listTaskToSite, ReqMeta(auth = false, test = true))
        )

        val item = MongoDBManager.collection<TaskWorkStation>().findOne()
        if (item == null) MongoDBManager.collection<TaskWorkStation>().insertOne(TaskWorkStation())
    }

    private fun listTaskToSite(ctx: Context) {
        val taskToSiteMap = Services.listTaskToSite()
        ctx.json(taskToSiteMap)
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

data class TaskWorkStation(
    @BsonId val id: ObjectId = ObjectId(),
    val taskId: String = "",
    val workStation: String = ""
)
