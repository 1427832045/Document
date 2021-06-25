package com.seer.srd.molex

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.seer.srd.Application
import com.seer.srd.BusinessError
import com.seer.srd.Error400
import com.seer.srd.I18N.locale
import com.seer.srd.db.MongoDBManager
import com.seer.srd.domain.Property
import com.seer.srd.eventbus.EventBus
import com.seer.srd.handler.ForceStatReq
import com.seer.srd.http.HttpRequestMapping
import com.seer.srd.http.HttpServer
import com.seer.srd.http.HttpServer.noAuth
import com.seer.srd.http.HttpServer.permit
import com.seer.srd.http.ReqMeta
import com.seer.srd.http.getReqLang
import com.seer.srd.molex.CustomComponent.extraComponents
import com.seer.srd.molex.MolexApp.onRobotTaskCreated
import com.seer.srd.molex.MolexApp.onRobotTaskFinished
import com.seer.srd.molex.MolexApp.onRobotTaskUpdated
import com.seer.srd.molex.Services.sentTaskList
import com.seer.srd.molex.Services.unSentPalletMap
import com.seer.srd.molex.stat.*
import com.seer.srd.robottask.*
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.route.service.VehicleService
import com.seer.srd.scheduler.backgroundFixedExecutor
import com.seer.srd.setVersion
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.user.CommonPermissionSet
import com.seer.srd.util.mapper
import com.seer.srd.vehicle.VehicleManager
import io.javalin.http.Context
import io.javalin.http.HandlerType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.lang.Exception
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun main() {
    MolexApp.init()
    Services.initSentTaskQueue()
    EventBus.robotTaskFinishedEventBus.add(::onRobotTaskFinished)
    EventBus.robotTaskCreatedEventBus.add(::onRobotTaskCreated)
    EventBus.robotTaskUpdatedEventBus.add(::onRobotTaskUpdated)
    Application.start()
    initStats()
    SiteAreaService.init()
}

object MolexApp {


    private val logger = LoggerFactory.getLogger(MolexApp::class.java)

    fun init() {
        setVersion("molex", "1.0.6")

        registerRobotTaskComponents(extraComponents)


        HttpServer.handle(
            HttpRequestMapping(
                HandlerType.GET, "listLightState", this::listLightState,
                ReqMeta(auth = false, test = true)
            )
        )

        HttpServer.handle(
            HttpRequestMapping(
                HandlerType.GET, "siteStateMap/:areaId", this::listSiteStateMap,
                ReqMeta(auth = false, test = true)
            )
        )

        HttpServer.handle(
            HttpRequestMapping(
                HandlerType.POST, "switchRed/:siteId", this::switch,
                ReqMeta(auth = false, test = true)
            )
        )

        HttpServer.handle(
            HttpRequestMapping(
                HandlerType.POST, "switchYellow/:siteId", this::switchYellow,
                ReqMeta(auth = false, test = true)
            )
        )

        HttpServer.handle(
            HttpRequestMapping(
                HandlerType.POST, "switchGreen/:siteId", this::switchGreen,
                ReqMeta(auth = false, test = true)
            )
        )

        HttpServer.handle(
            HttpRequestMapping(
                HandlerType.GET, "sentList", this::listSentTask,
                ReqMeta(auth = false, test = true)
            )
        )

        HttpServer.handle(
            HttpRequestMapping(
                HandlerType.GET, "stat/config", this::handleGetStatsConfig,
                noAuth()
            )
        )

        HttpServer.handle(
            HttpRequestMapping(
                HandlerType.GET, "stat", this::handleListStatRecords,
                noAuth()
            )
        )

        HttpServer.handle(
            HttpRequestMapping(
                HandlerType.POST, "stat/data-excel", this::handleStatsToExcel,
                permit(CommonPermissionSet.Stats.name)
            )
        )

        HttpServer.handle(
            HttpRequestMapping(
                HandlerType.POST, "getFromSites", this::getFromSites,
                permit(CommonPermissionSet.Stats.name)
            )
        )

        HttpServer.handle(
            HttpRequestMapping(
                HandlerType.POST, "getToSites", this::getToSites,
                permit(CommonPermissionSet.Stats.name)
            )
        )

        HttpServer.handle(
            HttpRequestMapping(
                HandlerType.POST, "stat/force", this::handleForceStat,
                permit(CommonPermissionSet.Stats.name)
            )
        )

        Application.initialize()
    }

    private fun getFromSites(ctx: Context) {
        ctx.json(CUSTOM_CONFIG.fromSites)
    }

    private fun getToSites(ctx: Context) {
        ctx.json(CUSTOM_CONFIG.toSites)
    }


    private fun listSiteStateMap(ctx: Context) {
      val areaId = ctx.pathParam("areaId")
      ctx.json(SiteAreaService.getAreaModBusById(areaId).siteStateMap)
    }

    private fun listLightState(ctx: Context) {
      ctx.json(
          mapOf(
          "yellow" to SiteAreaService.yellowTwinkle,
          "red" to SiteAreaService.yellowTwinkle,
          "green" to SiteAreaService.greenTwinkle
          )
      )
    }

    private fun switch(ctx: Context) {
        val siteId = ctx.pathParam("siteId")
        SiteAreaService.getAreaModBusBySiteId(siteId)?.switchRed(siteId)
    }

    private fun switchYellow(ctx: Context) {
        val siteId = ctx.pathParam("siteId")
        SiteAreaService.getAreaModBusBySiteId(siteId)?.switchYellow(siteId)
    }

    private fun switchGreen(ctx: Context) {
        val siteId = ctx.pathParam("siteId")
        SiteAreaService.getAreaModBusBySiteId(siteId)?.switchGreen(siteId)
    }

    private fun listSentTask(ctx: Context) {
        ctx.json(sentTaskList.toList())
        ctx.status(201)
    }

    private fun handleGetStatsConfig(ctx: Context) {
        val lang = getReqLang(ctx)
        val accounts = statAccounts.map { sa -> mapOf("name" to sa.type, "label" to locale("Stat_" + sa.type, lang)) }
        ctx.json(
            mapOf(
                "dayParts" to CUSTOM_CONFIG.moshiStatDayPartDefs,
                "accounts" to accounts
            )
        )
    }
    private fun handleListStatRecords(ctx: Context) {
        val level = ctx.queryParam("level") ?: throw Error400("NoLevel", "Missing level")
        val start = ctx.queryParam("start")
        val end = ctx.queryParam("end")
        val vehicleName = ctx.queryParam("vehicleName") ?: throw Error400("NoVehicle", "Missing vehicle")
        val types = ctx.queryParam("types")?.split(",")
        if (types.isNullOrEmpty()) {
            ctx.json(emptyList<Any>())
        } else {
            val c = MongoDBManager.collection<MoShiStatRecord>()
            val array2 = types.map { type ->
                val filters = arrayListOf(MoShiStatRecord::level eq StatTimeLevel.valueOf(level), MoShiStatRecord::type eq type)
                if (!start.isNullOrBlank()) filters.add(MoShiStatRecord::time gte start)
                if (!end.isNullOrBlank()) filters.add(MoShiStatRecord::time lte end)
                if (vehicleName != "ALL") filters.add(MoShiStatRecord::vehicleName eq vehicleName)
                val ofType = c.find(Filters.and(filters)).toList()
                ofType
            }
            ctx.json(array2)
        }
    }

    private fun handleStatsToExcel(ctx: Context) {
        val reqStr = ctx.body()
        val table = mapper.readValue(reqStr, jacksonTypeRef<List<List<Any>>>())

        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Stats Reports")

        table.forEachIndexed { i, rowData ->
            val row = sheet.createRow(i)
            rowData.forEachIndexed { j, cellData ->
                when (cellData) {
                    is String -> row.createCell(j).setCellValue(cellData)
                    is Long -> row.createCell(j).setCellValue(cellData.toDouble())
                    is Int -> row.createCell(j).setCellValue(cellData.toDouble())
                    is Float -> row.createCell(j).setCellValue(cellData.toDouble())
                    is Double -> row.createCell(j).setCellValue(cellData)
                }
            }
        }

        ctx.header("Content-Type", "application/octet-stream")
        ctx.header("Content-Disposition", """attachment; filename="report.xlsx"""")

        BufferedOutputStream(ctx.res.outputStream, 1024).use {
            workbook.write(it)
            it.flush()
        }
    }

    private fun handleForceStat(ctx: Context) {
        val req = ctx.bodyAsClass(ForceStatReq::class.java)
        val df = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val start = LocalDate.parse(req.start, df).atStartOfDay(ZoneId.systemDefault())
        val end = LocalDate.parse(req.end, df).atStartOfDay(ZoneId.systemDefault())
        backgroundFixedExecutor.submit { forceStat(start, end) }
        ctx.status(204)
    }

    fun onRobotTaskFinished(task: RobotTask) {
        try {
            if (task.def == CUSTOM_CONFIG.palletDef) {
                val toSiteId = task.persistedVariables["toSiteId"] as String
                MongoDBManager.collection<SiteFireTask>().updateOne(SiteFireTask::siteId eq toSiteId, pull(
                    SiteFireTask::fireTask, task.id
                ))
                if (SiteAreaService.fireTask[toSiteId]?.contains(task.id) == true) SiteAreaService.fireTask[toSiteId]?.remove(task.id)
                logger.debug("delete fire pallet task $toSiteId")

                if (task.state > RobotTaskState.Success && task.transports[CUSTOM_CONFIG.preToIndex].state == RobotTransportState.Success) {
                    val toType = task.persistedVariables["toType"] as String
                    val sites = StoreSiteService.listStoreSites().filter { it.type == toType }
                    sites.forEach {
                        if (SiteAreaService.redTwinkle[it.id] == true) SiteAreaService.redTwinkle[it.id] = false
                    }
                }
            }
            else if (task.def == CUSTOM_CONFIG.transferDef) {
                val from = task.persistedVariables["fromSite"] as String
                val to = task.persistedVariables["toSiteId"] as String
                MongoDBManager.collection<SiteFireTask>().updateOne(SiteFireTask::siteId eq from, pull(
                    SiteFireTask::fireTask, task.id
                ))
                if (SiteAreaService.fireTask[from]?.contains(task.id) == true) SiteAreaService.fireTask[from]?.remove(task.id)
                MongoDBManager.collection<SiteFireTask>().updateOne(SiteFireTask::siteId eq to, pull(
                    SiteFireTask::fireTask, task.id
                ))
                if (SiteAreaService.fireTask[to]?.contains(task.id) == true) SiteAreaService.fireTask[to]?.remove(task.id)
                logger.debug("delete fire task $from $to")
            }
            val vehicles = VehicleService.listVehicles()
            vehicles.forEach { v ->
                if (v.name == task.transports[0].processingRobot) {
                    if (v.processableCategories.contains(task.id)) {
                        val categories = v.processableCategories.filter { it != task.id }
                        VehicleManager.setVehicleProcessableCategories(v.name, categories)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("onRobotTaskFinished Error", e)
        } finally {
            if (sentTaskList.contains(task.id)) sentTaskList.remove(task.id)
            if (unSentPalletMap.containsKey(task.id)) unSentPalletMap.remove(task.id)
        }

    }

    fun onRobotTaskCreated(task: RobotTask) {

        logger.debug("task created [${task.def}]: from=${task.persistedVariables["fromSite"]}")
        if (task.def == CUSTOM_CONFIG.transferDef) {
            val from = task.persistedVariables["fromSite"] as String
            MongoDBManager.collection<SiteFireTask>().updateOne(SiteFireTask::siteId eq from, addToSet(
                SiteFireTask::fireTask, task.id
            ), UpdateOptions().upsert(true))
            SiteAreaService.fireTask[from]?.add(task.id)
            logger.debug("fire task $from")
        }
    }

    fun onRobotTaskUpdated(task: RobotTask) {
        if (task.def == CUSTOM_CONFIG.transferDef) {
            val from = task.persistedVariables["fromSite"] as String
            val fromSite = StoreSiteService.getStoreSiteById(from)
            if (task.transports[0].state == RobotTransportState.Success && fromSite?.locked == false) {
                MongoDBManager.collection<SiteFireTask>().updateOne(SiteFireTask::siteId eq from, pull(
                    SiteFireTask::fireTask, task.id
                ))
                if (SiteAreaService.fireTask[from]?.contains(task.id) == true) SiteAreaService.fireTask[from]?.remove(task.id)
            }
          logger.debug("delete fire task $from")
        }
       // 下发下一个栈板列任务
        if (task.def == CUSTOM_CONFIG.palletDef) {
            val fromType = task.persistedVariables["fromType"] as String?
            if (!fromType.isNullOrBlank() && fromType.contains("ST")) {
                val stFrom = task.persistedVariables["STFromSiteId"] as String?
                if (task.transports[CUSTOM_CONFIG.fromIndex].state >= RobotTransportState.Success && !stFrom.isNullOrBlank()) {
                    MongoDBManager.collection<SiteFireTask>().updateOne(SiteFireTask::siteId eq stFrom, pull(
                        SiteFireTask::fireTask, task.id
                    ))
                    if (SiteAreaService.fireTask[stFrom]?.contains(task.id) == true) SiteAreaService.fireTask[stFrom]?.remove(task.id)
                }
            }
            if (task.transports[CUSTOM_CONFIG.toIndex].state > RobotTransportState.Created) {
                try {
                    val toSiteId = task.persistedVariables["toSite"] as String
                    // 检查是否能生成下一个pallet任务
                    val num = if (task.persistedVariables["num"] is String)
                        (task.persistedVariables["num"] as String).toInt() else task.persistedVariables["num"] as Int
                    var index = task.persistedVariables["index"] as Int
                    val fromSiteId = task.persistedVariables["fromSite"] as String
                    val fromSite = StoreSiteService.getExistedStoreSiteById(fromSiteId)
                    val toSite = StoreSiteService.getExistedStoreSiteById(toSiteId)
                    val acTask = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq task.id)
                    if (index < num && acTask!= null && acTask.persistedVariables["sendNext"] != true) {
                        val taskDef = getRobotTaskDef(CUSTOM_CONFIG.palletDef) ?: throw BusinessError("no such task def [${CUSTOM_CONFIG.palletDef}]")
                        val newTask = buildTaskInstanceByDef(taskDef)
                        // 标记自动创建
                        newTask.persistedVariables["auto"] = true
                        // 标记当前任务的索引号
                        newTask.persistedVariables["index"] = ++index
                        // 标记共有几个任务需要生成
                        newTask.persistedVariables["num"] = num

                        // 标记最后一个栈板任务
                        newTask.persistedVariables["lastPalletTask"] = index == num

                        // 设置起点工作站
                        newTask.persistedVariables["fromSite"] = fromSite.type + "-0$index"
                        // 设置起点终点库区类型
                        newTask.persistedVariables["fromType"] = fromSite.type
                        newTask.persistedVariables["toType"] = toSite.type

                        if (fromSite.type in CUSTOM_CONFIG.recognizeTasks) {
                            val def = getRobotTaskDef(newTask.def)
                            if (def != null) {
                                val oldProperties = def.transports[CUSTOM_CONFIG.fromIndex].stages[0].properties
                                val properties =
                                    mapper.readValue(oldProperties, jacksonTypeRef<List<Property>>())
                                        .filter { it.key != "recognize" }.toMutableList().apply { add(Property("recognize", "true")) }
                                newTask.transports[CUSTOM_CONFIG.fromIndex].stages[0].properties =  mapper.writeValueAsString(properties)
                            }
                        }
                        RobotTaskService.saveNewRobotTask(newTask)

                        task.persistedVariables["sendNext"] = true
                        val pv = task.persistedVariables
                        MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq task.id, RobotTask::persistedVariables setTo pv)

                    }
                } catch (e: Exception) {
                    logger.error("create pallet task error", e)
                }
            }
        }
    }
}

//const val preFromIndex = 1
//const val fromIndex = 2
//const val preToIndex = 3
//const val toIndex = 4