package com.seer.srd.huaxin

import com.seer.srd.Application
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.eventbus.EventBus
import com.seer.srd.http.HttpRequestMapping
import com.seer.srd.http.HttpServer.handle
import com.seer.srd.http.ReqMeta
import com.seer.srd.http.WebSocketManager
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.route.service.PlantModelService
import com.seer.srd.route.service.VehicleService
import com.seer.srd.setVersion
import com.seer.srd.vehicle.Vehicle
import io.javalin.http.Context
import io.javalin.http.HandlerType
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

fun main() {
    setVersion("l2", "3.0.15.1")

    Application.initialize()

    HuaXinApp.init()

    EventBus.robotTaskFinishedEventBus.add(HuaXinApp::onRobotTaskFinished)
    EventBus.robotTaskUpdatedEventBus.add(HuaXinApp::onRobotTaskUpdated)

    Application.start()
}

object HuaXinApp {

    private val logger = LoggerFactory.getLogger(HuaXinApp::class.java)

    @Volatile
    private var checkFrom = false

    @Volatile
    private var checkTo = false

    @Volatile
    private var go: Boolean = false

    @Volatile
    private var end: Boolean = false

    @Volatile
    private var workStation = ""

    private val extraComponents: List<TaskComponentDef> = listOf(
        TaskComponentDef(
            "extra", "kuerle:go", "放行", "", false, listOf(
        ), false) { _, _ ->
            checkFrom = true
            if (!go) throw BusinessError("等待放行！！")
            checkFrom = false
            go = false
            end = false
        },
        TaskComponentDef(
            "extra", "kuerle:end", "归位", "", false, listOf(
        ), false) { _, _ ->
            checkTo = true
            if (!end) throw BusinessError("等待归位！！")
            go = false
        },
        TaskComponentDef(
            "extra", "kuerle:check", "确认归位", "", false, listOf(
        ), false) { _, _ ->
            if (!end) {
                checkTo = true
                throw BusinessError("等待归位！！")
            }
            else {
                logger.debug("确认归位")
                checkTo = false
                end = false
            }
        }
    )

    fun init() {

        registerRobotTaskComponents(extraComponents)

        handle(
            HttpRequestMapping(HandlerType.POST, "kuerle/go", ::go, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.GET, "kuerle/go", ::getGo, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.POST, "kuerle/end", ::end, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.GET, "kuerle/end", ::getEnd, ReqMeta(auth = false, test = true))
        )

        val item = MongoDBManager.collection<TaskWorkStation>().findOne()
        if (item == null) MongoDBManager.collection<TaskWorkStation>().insertOne(TaskWorkStation())
    }

    private fun go(ctx: Context) {
        val v = VehicleService.listVehicles()[0]
        val state = v.state
        if (state != Vehicle.State.IDLE && state != Vehicle.State.EXECUTING) throw BusinessError("AGV非调度状态！！")
        if (workStation.isBlank()) throw BusinessError("运行失败！！")
        val point = v.currentPosition
        var location = ""
        PlantModelService.getPlantModel().locations.values.forEach {
            it.attachedLinks.forEach { link ->
                if (link.point == point) location = link.location
            }
        }
        if (location != workStation) throw BusinessError("未到达位置【$workStation】！！")
        if (!checkFrom) throw BusinessError("运行失败！")
        if (go) throw BusinessError("已运行！！")
        else go = true
        ctx.status(200)
    }

    private fun getGo(ctx: Context) {
        ctx.json(mapOf("go" to checkFrom))
        ctx.status(200)
    }

    private fun end(ctx: Context) {
        val v = VehicleService.listVehicles()[0]
        val state = v.state
        if (state != Vehicle.State.IDLE && state != Vehicle.State.EXECUTING) throw BusinessError("AGV非调度状态！！")
        if (workStation.isBlank()) throw BusinessError("归位失败！！")
        val point = v.currentPosition
        var location = ""
        PlantModelService.getPlantModel().locations.values.forEach {
            it.attachedLinks.forEach { link ->
                if (link.point == point) location = link.location
            }
        }
        if (location != workStation) throw BusinessError("未到达位置【$workStation】！！")
        if (!checkTo) throw BusinessError("归位失败！")
        if (end) throw BusinessError("已归位！！")
        else end = true
        ctx.status(200)
    }

    private fun getEnd(ctx: Context) {
        ctx.json(mapOf("end" to checkTo))
        ctx.status(200)
    }

    fun onRobotTaskFinished(task: RobotTask) {
        if(task.def in (CUSTOM_CONFIG.taskDefs + CUSTOM_CONFIG.extraTaskDefs)) {
            go = false
            end = false
            checkFrom = false
            checkTo = false
            workStation = ""
            MongoDBManager.collection<TaskWorkStation>().updateOne(TaskWorkStation::taskId eq task.id,
                set(TaskWorkStation::taskId setTo "", TaskWorkStation::workStation setTo ""))
        }
    }

    fun onRobotTaskUpdated(task: RobotTask) {
        if(task.def in (CUSTOM_CONFIG.taskDefs + CUSTOM_CONFIG.extraTaskDefs)) {
            if (task.transports[1].processingRobot != null) {
                workStation = task.transports[1].stages[1].location
                MongoDBManager.collection<TaskWorkStation>().updateOne(TaskWorkStation::id exists true,
                    set(TaskWorkStation::taskId setTo task.id, TaskWorkStation::workStation setTo workStation))
            }
            else if (task.transports[0].processingRobot != null) {
                workStation = task.transports[0].stages[0].location
                MongoDBManager.collection<TaskWorkStation>().updateOne(TaskWorkStation::id exists true,
                    set(TaskWorkStation::taskId setTo task.id, TaskWorkStation::workStation setTo workStation))
            }
        }
    }

//    private fun setToSite(ctx: Context) {
//        val req = ctx.bodyAsClass(FromReq::class.java)
//        val from = req.from
//        val siteName = req.site
//        when (from) {
//            "PWToCircleFrom" -> fromSiteMap["PWToCircleFrom"] = siteName
//            "productToPrefixFrom" -> fromSiteMap["productToPrefixFrom"] = siteName
//            "clearToFixFrom" -> fromSiteMap["clearToFixFrom"] = siteName
//            "storeroomToFixFrom" -> fromSiteMap["storeroomToFixFrom"] = siteName
//            "storeToAssembleFrom" -> fromSiteMap["storeToAssembleFrom"] = siteName
//            "checkToProductFrom" -> fromSiteMap["checkToProductFrom"] = siteName
//            else -> throw BusinessError("起点:$siteName, 获取终点失败!!")
//        }
//        ctx.status(200)
//    }
}

data class TaskWorkStation(
    @BsonId val id: ObjectId = ObjectId(),
    val taskId: String = "",
    val workStation: String = ""
)
//data class FromReq(
//    val from: String,
//    val site: String
//)

//fun optionsSource() {
//        OperatorOptionsSource.addReader("PWToCircleFrom") {
//            val task = CUSTOM_CONFIG.task["PWToCircle"]
//            when (task) {
//              null -> listOf(
//                  SelectOption("T01", "T01"),
//                  SelectOption("T05", "T05")
//              )
//              else -> task.site1.map { SelectOption(it, it) }.toMutableList().apply {
//                  addAll(task.site2.map { SelectOption(it ,it) })
//              }
//            }
//        }
//
//        OperatorOptionsSource.addReader("PWToCircleTo") {
//            val f = fromSiteMap["PWToCircleFrom"]
//            val task = CUSTOM_CONFIG.task["PWToCircle"]
//            if (f == null || task == null) {
//                listOf(
//                    SelectOption("T01", "T01"),
//                    SelectOption("T05", "T05")
//                )
//            } else {
//                when {
//                  task.site1.contains(f) -> task.site2.map { SelectOption(it, it) }
//                  task.site2.contains(f) -> task.site1.map { SelectOption(it, it) }
//                  else -> listOf(
//                      SelectOption("T01", "T01"),
//                      SelectOption("T05", "T05")
//                  )
//                }
//            }
//        }
//
//        OperatorOptionsSource.addReader("productToPrefixFrom") {
//            val task = CUSTOM_CONFIG.task["productToPrefix"]
//            when (task) {
//                null -> listOf(
//                    SelectOption("T01", "T01"),
//                    SelectOption("T05", "T05")
//                )
//                else -> task.site1.map { SelectOption(it, it) }.toMutableList().apply {
//                    addAll(task.site2.map { SelectOption(it ,it) })
//                }
//            }
//        }
//
//        OperatorOptionsSource.addReader("productToPrefixTo") {
//            val f = fromSiteMap["productToPrefixFrom"]
//            val task = CUSTOM_CONFIG.task["productToPrefix"]
//            if (f == null || task == null) {
//                listOf(
//                    SelectOption("T03", "T03"),
//                    SelectOption("T20", "T20")
//                )
//            } else {
//                when {
//                    task.site1.contains(f) -> task.site2.map { SelectOption(it, it) }
//                    task.site2.contains(f) -> task.site1.map { SelectOption(it, it) }
//                    else -> listOf(
//                        SelectOption("T03", "T03"),
//                        SelectOption("T20", "T20")
//                    )
//                }
//            }
//        }
//
//        OperatorOptionsSource.addReader("clearToFixFrom") {
//            val task = CUSTOM_CONFIG.task["clearToFix"]
//            when (task) {
//                null -> listOf(
//                    SelectOption("T01", "T01"),
//                    SelectOption("T05", "T05")
//                )
//                else -> task.site1.map { SelectOption(it, it) }.toMutableList().apply {
//                    addAll(task.site2.map { SelectOption(it ,it) })
//                }
//            }
//        }
//
//        OperatorOptionsSource.addReader("clearToFixTo") {
//            val f = fromSiteMap["clearToFixFrom"]
//            val task = CUSTOM_CONFIG.task["clearToFix"]
//            if (f == null || task == null) {
//                listOf(
//                    SelectOption("T02", "T02"),
//                    SelectOption("T14", "T14"),
//                    SelectOption("T15", "T15"),
//                    SelectOption("T16", "T16"),
//                    SelectOption("T17", "T17"),
//                    SelectOption("T18", "T18")
//                )
//            } else {
//                when {
//                    task.site1.contains(f) -> task.site2.map { SelectOption(it, it) }
//                    task.site2.contains(f) -> task.site1.map { SelectOption(it, it) }
//                    else -> listOf(
//                        SelectOption("T02", "T02"),
//                        SelectOption("T14", "T14"),
//                        SelectOption("T15", "T15"),
//                        SelectOption("T16", "T16"),
//                        SelectOption("T17", "T17"),
//                        SelectOption("T18", "T18")
//                    )
//                }
//            }
//        }
//
//        OperatorOptionsSource.addReader("storeroomToFixFrom") {
//            val task = CUSTOM_CONFIG.task["storeroomToFix"]
//            when (task) {
//                null -> listOf(
//                    SelectOption("T01", "T01"),
//                    SelectOption("T05", "T05")
//                )
//                else -> task.site1.map { SelectOption(it, it) }.toMutableList().apply {
//                    addAll(task.site2.map { SelectOption(it ,it) })
//                }
//            }
//        }
//
//        OperatorOptionsSource.addReader("storeroomToFixTo") {
//            val f = fromSiteMap["storeroomToFixFrom"]
//            val task = CUSTOM_CONFIG.task["storeroomToFix"]
//            if (f == null || task == null) {
//                listOf(
//                    SelectOption("T04", "T04"),
//                    SelectOption("T14", "T14"),
//                    SelectOption("T15", "T15"),
//                    SelectOption("T16", "T16"),
//                    SelectOption("T17", "T17"),
//                    SelectOption("T18", "T18")
//                )
//            } else {
//                when {
//                    task.site1.contains(f) -> task.site2.map { SelectOption(it, it) }
//                    task.site2.contains(f) -> task.site1.map { SelectOption(it, it) }
//                    else -> listOf(
//                        SelectOption("T04", "T04"),
//                        SelectOption("T14", "T14"),
//                        SelectOption("T15", "T15"),
//                        SelectOption("T16", "T16"),
//                        SelectOption("T17", "T17"),
//                        SelectOption("T18", "T18")
//                    )
//                }
//            }
//        }
//
//        OperatorOptionsSource.addReader("storeToAssembleFrom") {
//            val task = CUSTOM_CONFIG.task["storeToAssemble"]
//            when (task) {
//                null -> listOf(
//                    SelectOption("T01", "T01"),
//                    SelectOption("T05", "T05")
//                )
//                else -> task.site1.map { SelectOption(it, it) }.toMutableList().apply {
//                    addAll(task.site2.map { SelectOption(it ,it) })
//                }
//            }
//        }
//
//        OperatorOptionsSource.addReader("storeToAssembleTo") {
//            val f = fromSiteMap["storeToAssembleFrom"]
//            val task = CUSTOM_CONFIG.task["storeToAssemble"]
//            if (f == null || task == null) {
//                listOf(
//                    SelectOption("T19", "T19"),
//                    SelectOption("T06", "T06"),
//                    SelectOption("T07", "T07"),
//                    SelectOption("T08", "T08"),
//                    SelectOption("T09", "T09"),
//                    SelectOption("T10", "T10"),
//                    SelectOption("T11", "T11"),
//                    SelectOption("T12", "T12")
//                )
//            } else {
//                when {
//                    task.site1.contains(f) -> task.site2.map { SelectOption(it, it) }
//                    task.site2.contains(f) -> task.site1.map { SelectOption(it, it) }
//                    else -> listOf(
//                        SelectOption("T19", "T19"),
//                        SelectOption("T06", "T06"),
//                        SelectOption("T07", "T07"),
//                        SelectOption("T08", "T08"),
//                        SelectOption("T09", "T09"),
//                        SelectOption("T10", "T10"),
//                        SelectOption("T11", "T11"),
//                        SelectOption("T12", "T12")
//                    )
//                }
//            }
//        }
//
//        OperatorOptionsSource.addReader("checkToProductFrom") {
//            val task = CUSTOM_CONFIG.task["checkToProduct"]
//            when (task) {
//                null -> listOf(
//                    SelectOption("T01", "T01"),
//                    SelectOption("T05", "T05")
//                )
//                else -> task.site1.map { SelectOption(it, it) }.toMutableList().apply {
//                    addAll(task.site2.map { SelectOption(it ,it) })
//                }
//            }
//        }
//
//        OperatorOptionsSource.addReader("checkToProductTo") {
//            val f = fromSiteMap["checkToProductFrom"]
//            val task = CUSTOM_CONFIG.task["checkToProduct"]
//            if (f == null || task == null) {
//                listOf(
//                    SelectOption("T13", "T13"),
//                    SelectOption("T20", "T20")
//                )
//            } else {
//                when {
//                    task.site1.contains(f) -> task.site2.map { SelectOption(it, it) }
//                    task.site2.contains(f) -> task.site1.map { SelectOption(it, it) }
//                    else -> listOf(
//                        SelectOption("T13", "T13"),
//                        SelectOption("T20", "T20")
//                    )
//                }
//            }
//        }