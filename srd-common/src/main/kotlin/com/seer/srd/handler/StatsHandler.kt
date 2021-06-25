package com.seer.srd.handler

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import com.mongodb.client.model.Filters.and
import com.seer.srd.CONFIG
import com.seer.srd.Error400
import com.seer.srd.I18N.locale
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.http.getReqLang
import com.seer.srd.scheduler.backgroundFixedExecutor
import com.seer.srd.stats.StatRecord
import com.seer.srd.stats.StatTimeLevel
import com.seer.srd.stats.forceStat
import com.seer.srd.stats.statAccounts
import com.seer.srd.util.mapper
import io.javalin.http.Context
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.litote.kmongo.eq
import org.litote.kmongo.gte
import org.litote.kmongo.lte
import java.io.BufferedOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun handleGetStatsConfig(ctx: Context) {
    val lang = getReqLang(ctx)
    val accounts = statAccounts.map { sa -> mapOf("name" to sa.type, "label" to locale("Stat_" + sa.type, lang)) }
    ctx.json(
        mapOf(
            "dayParts" to CONFIG.statDayPartDefs,
            "accounts" to accounts
        )
    )
}

fun handleListStatRecords(ctx: Context) {
    val level = ctx.queryParam("level") ?: throw Error400("NoLevel", "Missing level")
    val start = ctx.queryParam("start")
    val end = ctx.queryParam("end")
    val types = ctx.queryParam("types")?.split(",")
    val timeUnit = ctx.queryParam("timeUnit")
    if (types.isNullOrEmpty()) {
        ctx.json(emptyList<Any>())
    } else {
        val c = collection<StatRecord>()
        val array2 = types.map { type ->
            val filters = arrayListOf(StatRecord::level eq StatTimeLevel.valueOf(level), StatRecord::type eq type)
            if (!start.isNullOrBlank()) filters.add(StatRecord::time gte start)
            if (!end.isNullOrBlank()) filters.add(StatRecord::time lte end)
            val ofType = c.find(and(filters)).toList()
            ofType
        }
        when (timeUnit?.toLowerCase()) {
            "minute" -> ctx.json(array2.map { li ->
                li.map { StatRecord(
                    it.id,
                    it.type,
                    it.level,
                    it.time,
                    if (it.type.toLowerCase().contains("time")
                        || it.type.toLowerCase().contains("avg")
                        || it.type.toLowerCase().contains("sum"))
                        it.value / 60 else it.value,
                    it.recordedOn) }
            })
            "hour" -> ctx.json(array2.map { li ->
                li.map { StatRecord(
                    it.id,
                    it.type,
                    it.level,
                    it.time,
                    if (it.type.toLowerCase().contains("time")
                        || it.type.toLowerCase().contains("avg")
                        || it.type.toLowerCase().contains("sum"))
                        it.value / 3600 else it.value,
                    it.recordedOn) }
            })
            else -> ctx.json(array2)
        }

    }
}

fun handleStatsToExcel(ctx: Context) {
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


class ForceStatReq(
    val start: String,
    val end: String
)

fun handleForceStat(ctx: Context) {
    val req = ctx.bodyAsClass(ForceStatReq::class.java)
    val df = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val start = LocalDate.parse(req.start, df).atStartOfDay(ZoneId.systemDefault())
    val end = LocalDate.parse(req.end, df).atStartOfDay(ZoneId.systemDefault())
    backgroundFixedExecutor.submit { forceStat(start, end) }
    ctx.status(204)
}
