package com.seer.srd.handler

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import com.mongodb.client.model.Filters.*
import com.mongodb.client.model.Projections.include
import com.mongodb.client.model.Sorts.*
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.domain.PagingResult
import com.seer.srd.eventbus.EventBus.onRobotTaskRemoved
import com.seer.srd.http.getPageNo
import com.seer.srd.http.getPageSize
import com.seer.srd.robottask.*
import com.seer.srd.robottask.component.listComponentDefs
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.util.mapper
import io.javalin.http.Context
import org.apache.commons.lang3.time.DateUtils
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.bson.Document
import org.bson.conversions.Bson
import org.litote.kmongo.`in`
import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.util.regex.Pattern

private val LOG = LoggerFactory.getLogger("com.seer.srd.handler")

fun handleListRobotTasks(ctx: Context) {
    val pageNo = getPageNo(ctx)
    val pageSize = getPageSize(ctx)

    val sort = listSort(ctx.queryParam("sort"))
    val criteria = parseCriteria(ctx.queryParamMap())

    val c = collection<RobotTask>()
    val total = c.countDocuments(criteria)
    val page = c.find(criteria).sort(sort).skip((pageNo - 1) * pageSize).limit(pageSize).toList()

    ctx.json(PagingResult(total, page, pageNo, pageSize))
}

private fun parseCriteria(query: Map<String, List<String>>): Bson {
    val criteriaItems: MutableList<Bson> = ArrayList()

    val id = query["id"]?.get(0)?.trim()
    if (!id.isNullOrBlank()) criteriaItems.add(regex("_id", ".*" + Pattern.quote(id) + ".*"))

    val outOrderNo = query["outOrderNo"]?.get(0)?.trim()
    if (!outOrderNo.isNullOrBlank()) criteriaItems.add(eq("outOrderNo", outOrderNo))

    val statesStr = query["states"]?.get(0)
    if (!statesStr.isNullOrBlank()) {
        val states = statesStr.split(",").map { it.toInt() }
        criteriaItems.add(`in`("state", states))
    }

    val typesStr = query["types"]?.get(0)
    if (!typesStr.isNullOrBlank()) {
        val types = typesStr.split(",")
        criteriaItems.add(`in`("def", types))
    }

    val createdOnStartStr = query["createdOnStart"]?.get(0)
    if (!createdOnStartStr.isNullOrBlank()) {
        val createdOnStart = DateUtils.parseDate(createdOnStartStr, "yyyy-MM-dd")
        criteriaItems.add(gte("createdOn", createdOnStart))
    }

    val createdOnEndStr = query["createdOnEnd"]?.get(0)
    if (!createdOnEndStr.isNullOrBlank()) {
        var createdOnEnd = DateUtils.parseDate(createdOnEndStr, "yyyy-MM-dd")
        createdOnEnd = DateUtils.addDays(createdOnEnd, 1)
        criteriaItems.add(lt("createdOn", createdOnEnd))
    }

    val extraStr = query["extra"]?.get(0)
    if (!extraStr.isNullOrBlank()) {
        val extra = mapper.readTree(extraStr)
        for (field in extra.fieldNames()) {
            criteriaItems.add(eq(field, extra[field].asText()))
        }
    }

    return if (criteriaItems.isEmpty()) Document() else and(criteriaItems)
}

// 列出与我有关的任务
fun handleListMyRobotTask(ctx: Context) {
    val finalCriteriaItems: MutableList<Bson> = ArrayList()

    val cByWork: MutableList<Bson> = ArrayList()
    val workType = ctx.queryParam("workType")
    if (!workType.isNullOrBlank()) cByWork.add(eq("workTypes", workType))

    val workStation = ctx.queryParam("workStation")
    if (!workStation.isNullOrBlank()) cByWork.add(eq("workStations", workStation))
    // TODO if(query.operator) orItems.push(singleCriteria())
    if (cByWork.isNotEmpty()) finalCriteriaItems.add(or(cByWork))

    val def = ctx.queryParam("def")
    if (!def.isNullOrBlank()) finalCriteriaItems.add(eq("def", def))
    val unfinished = !ctx.queryParam("unfinished").isNullOrBlank()
    if (unfinished) finalCriteriaItems.add(lt("state", RobotTaskState.Success))

    val pageNo = getPageNo(ctx)
    val pageSize = if (unfinished) -1 else getPageSize(ctx)
    val criteria = if (finalCriteriaItems.isNotEmpty()) and(finalCriteriaItems) else Document()

    val sort = listSort(ctx.queryParam("sort"))

    val c = collection<RobotTask>().apply {
        this.createIndex(sort)
    }
    val total = c.countDocuments(criteria)
    var query = c.find(criteria).sort(sort)
    if (pageSize > 0) query = query.skip((pageNo - 1) * pageSize).limit(pageSize)
    val page = query.toList()

    ctx.json(PagingResult(total, page, pageNo, pageSize))
}

private fun listSort(sortStr: String?): Bson {
    if (sortStr.isNullOrBlank()) return orderBy(
        descending("modifiedOn"),
        descending("createdOn"),
        descending("_id")
    )

    val sortNode = mapper.readTree(sortStr)
    val items = sortNode.toList().map { item ->
        if ("desc" == item["order"].asText()) descending(item["column"].asText())
        else ascending(item["column"].asText())
    }
    return orderBy(items)
}

fun handleTasksToExcel(ctx: Context) {
    val reqStr = ctx.body()
    val table = mapper.readValue(reqStr, jacksonTypeRef<List<List<Any>>>())

    val workbook = XSSFWorkbook()
    val sheet = workbook.createSheet("Tasks")

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

fun handleRemoveRobotTask(ctx: Context) {
    val id = ctx.pathParam("id")

    RobotTaskService.removeTask(id)

    ctx.status(204)
}

fun handleReleaseOwnedSites(ctx: Context) {
    val id = ctx.pathParam("id")
    StoreSiteService.releaseOwnedSites(id, "FromTaskAbort")
    ctx.status(204)
}

fun handleAbortRobotTask(ctx: Context) {
    val id = ctx.pathParam("id")

    RobotTaskService.abortTask(id)

    ctx.status(204)
}

class AdjustRobotTaskPriorityReq(
    var priority: Int = 0
)

fun handleAdjustRobotTaskPriority(ctx: Context) {
    val id = ctx.pathParam("id")
    val req = ctx.bodyAsClass(AdjustRobotTaskPriorityReq::class.java)

    RobotTaskService.updateRobotTaskPriority(id, req.priority)

    ctx.status(204)
}

fun handleRemoveAllFinishedRobotTasks(ctx: Context) {
    val cTask = collection<RobotTask>()
    val finishedStates = listOf(RobotTaskState.Success, RobotTaskState.Failed, RobotTaskState.Aborted)
    val taskIds = cTask.find(RobotTask::state `in` finishedStates).projection(include("_id")).map { it.id }.toList()
    cTask.deleteMany(RobotTask::id `in` taskIds)

    LOG.info("Remove finished tasks ${taskIds.size}")

    onRobotTaskRemoved()

    ctx.status(204)
}

fun handleListRobotTaskDefs(ctx: Context) {
    ctx.json(listRobotTaskDefs())
}

fun handleGetRobotTaskDef(ctx: Context) {
    val name = ctx.pathParam("name")
    val def = getRobotTaskDef(name)
    if (def == null)  ctx.json(mapOf("code" to "NoSuchTaskDef", "message" to name))
    else ctx.json(def)
}

fun handleUpdateRobotTaskDef(ctx: Context) {
    val name = ctx.pathParam("name")
    val req = ctx.bodyAsClass(RobotTaskDef::class.java)
    req.name = name
    updateRobotTaskDefs(listOf(req))

    ctx.status(204)
}

class ImportRobotTaskDefsReq(
    var defs: Map<String, RobotTaskDef> = emptyMap()
)

fun handleImportRobotTaskDefs(ctx: Context) {
    val req = ctx.bodyAsClass(ImportRobotTaskDefsReq::class.java)
    updateRobotTaskDefs(req.defs.values.toList())
    ctx.status(204)
}

fun handleRemoveRobotTaskDef(ctx: Context) {
    val name = ctx.pathParam("name")
    removeRobotTaskDef(name)
    ctx.status(204)
}

fun handleListComponentsDefs(ctx: Context) {
    ctx.json(listComponentDefs())
}