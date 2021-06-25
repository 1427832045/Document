package com.seer.srd.moshi

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.seer.srd.Application
import com.seer.srd.Error400
import com.seer.srd.I18N.locale
import com.seer.srd.db.MongoDBManager
import com.seer.srd.eventbus.EventBus
import com.seer.srd.handler.ForceStatReq
import com.seer.srd.http.HttpRequestMapping
import com.seer.srd.http.HttpServer
import com.seer.srd.http.HttpServer.noAuth
import com.seer.srd.http.HttpServer.permit
import com.seer.srd.http.ReqMeta
import com.seer.srd.http.getReqLang
import com.seer.srd.moshi.MsApp.onRobotTaskCreated
import com.seer.srd.moshi.MsApp.onRobotTaskFinished
import com.seer.srd.moshi.MsApp.onRobotTaskUpdated
import com.seer.srd.moshi.stat.*
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTransportState
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.scheduler.backgroundFixedExecutor
import com.seer.srd.setVersion
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.user.CommonPermissionSet
import com.seer.srd.util.mapper
import io.javalin.http.Context
import io.javalin.http.HandlerType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object MsApp {


    private val logger = LoggerFactory.getLogger(MsApp::class.java)

    fun init() {
        setVersion("l2", "3.0.11")

        registerRobotTaskComponents(extraComponents)

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
        if (task.def.contains("transfer")) {
            val from = task.persistedVariables["fromSite"] as String
            val to = task.persistedVariables["toSite"] as String
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
    }

    fun onRobotTaskCreated(task: RobotTask) {
        if (task.def.contains("transfer")) {
            val from = task.persistedVariables["fromSite"] as String
            val to = task.persistedVariables["toSite"] as String
            MongoDBManager.collection<SiteFireTask>().updateOne(SiteFireTask::siteId eq from, addToSet(
                SiteFireTask::fireTask, task.id
            ), UpdateOptions().upsert(true))
            SiteAreaService.fireTask[from]?.add(task.id)
            MongoDBManager.collection<SiteFireTask>().updateOne(SiteFireTask::siteId eq to, addToSet(
                SiteFireTask::fireTask, task.id
            ), UpdateOptions().upsert(true))
            SiteAreaService.fireTask[to]?.add(task.id)
            logger.debug("fire task $from $to")
        }
    }

    fun onRobotTaskUpdated(task: RobotTask) {
        if (task.def.contains("transfer")) {
            val from = task.persistedVariables["fromSite"] as String
            val fromSite = StoreSiteService.getStoreSiteById(from)
            if (task.transports[0].state == RobotTransportState.Success && fromSite?.locked == false) {
                MongoDBManager.collection<SiteFireTask>().updateOne(SiteFireTask::siteId eq from, pull(
                    SiteFireTask::fireTask, task.id
                ))
                if (SiteAreaService.fireTask[from]?.contains(task.id) == true) SiteAreaService.fireTask[from]?.remove(task.id)
            }
          logger.debug("delete fire task $from")
//            val to = task.persistedVariables["toSite"] as String
//            val toSite = StoreSiteService.getStoreSiteById(to)
//            if (toSite?.locked == false) {
//                MongoDBManager.collection<SiteFireTask>().updateOne(SiteFireTask::siteId eq to, pull(
//                    SiteFireTask::fireTask, task.id
//                ))
//                if (SiteAreaService.fireTask[to]?.contains(task.id) == true) SiteAreaService.fireTask[to]?.remove(task.id)
//            }
        }
    }
}

fun main() {
    MsApp.init()
    EventBus.robotTaskFinishedEventBus.add(::onRobotTaskFinished)
    EventBus.robotTaskCreatedEventBus.add(::onRobotTaskCreated)
    EventBus.robotTaskUpdatedEventBus.add(::onRobotTaskUpdated)
    Application.start()
    initStats()
    SiteAreaService.init()
}