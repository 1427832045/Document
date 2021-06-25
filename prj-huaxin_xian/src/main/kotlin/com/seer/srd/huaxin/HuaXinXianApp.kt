package com.seer.srd.huaxin

import com.seer.srd.Application
import com.seer.srd.BusinessError
import com.seer.srd.Error400
import com.seer.srd.db.MongoDBManager
import com.seer.srd.eventbus.EventBus
import com.seer.srd.http.Handlers
import com.seer.srd.http.HttpRequestMapping
import com.seer.srd.http.HttpServer.handle
import com.seer.srd.http.HttpServer.noAuth
import com.seer.srd.http.ReqMeta
import com.seer.srd.huaxin.CustomComponent.extraComponents
import com.seer.srd.huaxin.Services.checkFromMap
import com.seer.srd.huaxin.Services.checkToMap
import com.seer.srd.huaxin.Services.getLocationById
import com.seer.srd.huaxin.Services.passLocations
import com.seer.srd.huaxin.Services.rolledLocations
import com.seer.srd.huaxin.Services.waitingPassMap
import com.seer.srd.huaxin.Services.waitingRolledMap
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.route.service.VehicleService
import com.seer.srd.setVersion
import com.seer.srd.storesite.StoreSiteService
import io.javalin.http.Context
import io.javalin.http.HandlerType
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.slf4j.LoggerFactory

fun main() {
    setVersion("l2", "3.0.17")

    Application.initialize()

    HuaXinXianApp.init()

    EventBus.robotTaskFinishedEventBus.add(Services::onRobotTaskFinished)
    EventBus.robotTaskUpdatedEventBus.add(Services::onRobotTaskUpdated)

    Application.start()
}

object HuaXinXianApp {

    private val logger = LoggerFactory.getLogger(HuaXinXianApp::class.java)

    fun init() {

        registerRobotTaskComponents(extraComponents)

        handle(
            HttpRequestMapping(HandlerType.POST, "xian/go/:name", ::go, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.GET, "xian/go/:name", ::getGo, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.POST, "xian/end/:name", ::end, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.GET, "xian/end/:name", ::getEnd, ReqMeta(auth = false, test = true)),

            HttpRequestMapping(HandlerType.POST, "1F/AsToClear", ::asToClear, ReqMeta(auth = false, test = false)),
            HttpRequestMapping(HandlerType.POST, "1F/ASToBurnInRoom", ::asToBurnInRoom, ReqMeta(auth = false, test = false)),
            HttpRequestMapping(HandlerType.POST, "2F/ASToSelfCloseFix", ::asToSelfCloseFix, ReqMeta(auth = false, test = false)),
            HttpRequestMapping(HandlerType.POST, "1F/ASToLine", ::asToLine, ReqMeta(auth = false, test = false)),
            HttpRequestMapping(HandlerType.POST, "1F/ASToElectricMaterialFix", ::asToElectricMaterialFix, ReqMeta(auth = false, test = false)),
            HttpRequestMapping(HandlerType.POST, "1F/ASToRelayCheck", ::asToRelayCheck, ReqMeta(auth = false, test = false)),
            HttpRequestMapping(HandlerType.POST, "1F/call", ::createTask, ReqMeta(auth = false, test = false)),

            // 立库对接
            HttpRequestMapping(HandlerType.POST, "1F/out/pass", ::pass, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.POST, "1F/in/rolled", ::inRolled, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.POST, "1F/in/pass", ::pass, ReqMeta(auth = false, test = true))
        )

        val mockHandler = Handlers("seer")
        mockHandler.post("outstockbegin", ::mockReady, ReqMeta(auth = false, test = true))
        mockHandler.post("instockbegin", ::mockReady, ReqMeta(auth = false, test = true))
        mockHandler.post("outstockend", ::mockAgvLoaded, ReqMeta(auth = false, test = true))
        mockHandler.post("1F/in/agv-unloaded", ::mockAgvLoaded, ReqMeta(auth = false, test = true))

//        val item = MongoDBManager.collection<TaskWorkStation>().findOne()
//        if (item == null) MongoDBManager.collection<TaskWorkStation>().insertOne(TaskWorkStation())
    }

    private fun createTask(ctx: Context) {
        val req = ctx.bodyAsClass(MesTask::class.java)
        val from = req.from
        val to = req.to
        var taskId = ""
        if (!from.contains("LK") && !to.contains("LK")) throw Error400("BusinessError", "必须包含[LK]类型")
        if (from == "" ||  to == "") throw Error400("BusinessError", "起点和终点都不能为空")
        if (from == to) throw Error400("BusinessError", "起点和终点不能相同")
        if (from.contains("QJQ") || to.contains("QJQ"))
            taskId = Services.createTask(from, to, "ASToClear")
        else if (from.contains("ZH") || to.contains("ZH"))
            taskId = Services.createTask(from, to, "ASToSelfCloseFix")
        else if (from.contains("DZX") || to.contains("DZX"))
            taskId = Services.createTask(from, to, "ASToElectricMaterialFix")
        else if (from.contains("LS-2") || to.contains("LS-2"))
            taskId = Services.createTask(from, to, "ASToLine")
        else if (from.contains("DPS") || to.contains("DPS"))
            taskId = Services.createTask(from, to, "ASToBurnInRoom")
        else if (from.contains("JYS") || to.contains("JYS"))
            taskId = Services.createTask(from, to, "ASToRelayCheck")
        else {
            if (from.contains("LK")) throw Error400("NoSuchSite",to)
            else throw Error400("NoSuchSite",from)
        }
        ctx.json(mapOf("taskId" to taskId))
    }
    private fun asToClear(ctx: Context) {
        val req = ctx.bodyAsClass(MesTask::class.java)
        val from = req.from
        val to = req.to
        if (!from.contains("LK2") && !to.contains("LK2")) throw Error400("400", "必须包含[LK2]")
        if (!from.contains("QJQ") && !to.contains("QJQ")) throw Error400("400", "必须包含[QJQ]")
        Services.createTask(from, to, "ASToClear")
    }

    private fun asToBurnInRoom(ctx: Context) {
        val req = ctx.bodyAsClass(MesTask::class.java)
        val from = req.from
        val to = req.to
        if (!from.contains("LK2") && !to.contains("LK2")) throw Error400("400", "必须包含[LK2]")
        if (!from.contains("DPS") && !to.contains("DPS")) throw Error400("400", "必须包含[DPS]")
        Services.createTask(from, to, "ASToBurnInRoom")
    }

    private fun asToSelfCloseFix(ctx: Context) {
        val req = ctx.bodyAsClass(MesTask::class.java)
        val from = req.from
        val to = req.to
        if (!from.contains("LK2") && !to.contains("LK2")) throw Error400("400", "必须包含[LK2]")
        if (!from.contains("ZH") && !to.contains("ZH")) throw Error400("400", "必须包含[ZH]类型库位")
        Services.createTask(from, to, "ASToSelfCloseFix")
    }

    private fun asToLine(ctx: Context) {
        val req = ctx.bodyAsClass(MesTask::class.java)
        val from = req.from
        val to = req.to
        if (!from.contains("LK2") && !to.contains("LK2")) throw Error400("400", "必须包含[LK2]")
        if (!from.contains("LS-2") && !to.contains("LS-2")) throw Error400("400", "必须包含[LS-2]")
        Services.createTask(from, to, "ASToLine")
    }

    private fun asToElectricMaterialFix(ctx: Context) {
        val req = ctx.bodyAsClass(MesTask::class.java)
        val from = req.from
        val to = req.to
        if (!from.contains("LK2") && !to.contains("LK2")) throw Error400("400", "必须包含[LK2]")
        if (!from.contains("DZX") && !to.contains("DZX")) throw Error400("400", "必须包含[DZX]")
        Services.createTask(from, to, "ASToElectricMaterialFix")
    }

    private fun asToRelayCheck(ctx: Context) {
        val req = ctx.bodyAsClass(MesTask::class.java)
        val from = req.from
        val to = req.to
        if (!from.contains("LK2") && !to.contains("LK2")) throw Error400("400", "必须包含[LK2]")
        if (!from.contains("JYS") && !to.contains("JYS")) throw Error400("400", "必须包含[JYS]")
        Services.createTask(from, to, "ASToRelayCheck")
    }

    private fun pass(ctx: Context) {
//        val station = ctx.bodyAsClass(MesReq::class.java).station
        val station = ctx.queryParam("station") as String
        val location = getLocationById(station)
        StoreSiteService.getExistedStoreSiteById(location)

        if (CUSTOM_CONFIG.preSendSignal) {
            if(!passLocations.add(getLocationById(station))) logger.warn("added pass location $location again")
            logger.debug("received [$location] pass signal from mes")
            } else {
                if (waitingPassMap[location] == true) {
                    if(!passLocations.add(location)) logger.warn("added pass location $location again!")
                    logger.debug("received [$location] pass signal from mes")
                } else {
                    if(!passLocations.add(location)) logger.warn("added pass location $location again!!")
                    logger.warn("received [$location] pass signal from mes without srd request")
                    ctx.json(mapOf("warning" to "received [$location] pass signal from mes without srd request"))
              }
          }
      ctx.status(200)
    }

    private fun inRolled(ctx: Context) {
//        val station = ctx.bodyAsClass(MesReq::class.java).station
        val station = ctx.queryParam("station") as String
        val location = getLocationById(station)
        StoreSiteService.getExistedStoreSiteById(location)

        if (CUSTOM_CONFIG.preSendSignal) {
            if(!rolledLocations.add(location)) logger.warn("added rolled location $location again")
            logger.debug("received [$location] rolled signal from mes")
        } else {
            if (waitingRolledMap[location] == true) {
                if(!rolledLocations.add(location)) logger.warn("added rolled location $location again!")
                logger.debug("received [$location] rolled signal from mes")
            } else {
                if(!rolledLocations.add(location)) logger.warn("added rolled location $location again!!")
                logger.warn("received [$location] rolled signal from mes without srd request")
                ctx.json(mapOf("warning" to "received [$location] rolled signal from mes without srd request"))
            }
        }

        ctx.status(200)
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

//data class TaskWorkStation(
//    @BsonId val id: ObjectId = ObjectId(),
//    val taskId: String = "",
//    val workStation: String = ""
//)

data class MesReq(
    val station: Int = 0
)

data class MesTask(
    val from: String = "",
    val to: String = ""
)