package com.seer.srd.intelCD.customDoorHandler

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.device.DoorStatusRecord
import com.seer.srd.http.Handlers
import com.seer.srd.http.ReqMeta
import com.seer.srd.http.getPageNo
import com.seer.srd.http.getPageSize
import com.seer.srd.route.service.TransportOrderOutput2
import io.javalin.http.Context
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.time.Instant

private val logger = LoggerFactory.getLogger("com.seer.srd.customDoorHandler")

fun registerCustomDoorHttpHandlers() {
    val extDoor = Handlers("ext/door")
    extDoor.get("list-logs", ::listDoorLogs, ReqMeta(test = true, auth = false))
    extDoor.get("list-errors", ::listDoorErrors, ReqMeta(test = true, auth = false))
    extDoor.get("list-open-opts", ::listOpenOperations, ReqMeta(test = true, auth = false))
}

fun listDoorErrors(ctx: Context) {
    ctx.json(listDoorStatusRecordByItem(QueryParam().buildQueryParam(ctx).copy(item = "error")))
    ctx.status(200)
}

fun listOpenOperations(ctx: Context) {
    ctx.json(listDoorStatusRecordByItem(QueryParam().buildQueryParam(ctx).copy(item = "open")))
    ctx.status(200)
}

fun listDoorLogs(ctx: Context) {
    ctx.json(listDoorStatusRecordByItem(QueryParam().buildQueryParam(ctx)))
    ctx.status(200)
}

fun listDoorStatusRecordByItem(query: QueryParam): DoorResult {
    val startT = buildTime(query.start)
    val endT = buildTime(query.end)
    val item = query.item
    val filters = arrayListOf(DoorStatusRecord::value eq "true")
    if (item != null) filters.add(DoorStatusRecord::item eq item)
    if (startT != null) filters.add(DoorStatusRecord::timeStamp gte startT)
    if (endT != null) filters.add(DoorStatusRecord::timeStamp lt endT)

    val c = MongoDBManager.collection<DoorStatusRecord>()
    val data = if (filters.isEmpty()) c.find() else c.find(Filters.and(filters))
    val total = data.toMutableList().size
    val list = data.sort(Sorts.descending("timeStamp"))
        .skip((query.pageNo - 1) * query.pageSize).limit(query.pageSize).toMutableList()
        .map {
            Details(it.name, it.item, it.value, it.timeStamp)
        }
    return DoorResult(total, list)
}

fun buildTime(dateStr: String?): Instant? {
    try {
        if (dateStr == null) return null
        return Instant.parse("${dateStr}T08:00:00.000Z")
    } catch (e: Exception) {
        throw BusinessError("Error date format $dateStr, supported format like YYYY-MM-DD")
    }
}

data class QueryParam(
    val pageNo: Int = 1,
    val pageSize: Int = 20,
    val item: String? = null,
    val start: String? = null,
    val end: String? = null
) {
    fun buildQueryParam(ctx: Context): QueryParam {
        val pageNo = getPageNo(ctx)
        val pageSize = getPageSize(ctx)
        val start = ctx.queryParam("start")
        val end = ctx.queryParam("end")
        val item = ctx.queryParam("item")
        if (!item.isNullOrBlank() && !listOf("open", "error").contains(item))
            throw BusinessError("")
        return QueryParam(pageNo, pageSize, item, start, end)
    }
}

data class DoorResult(
    val total: Int = 0,
    val details: List<Details> = emptyList()
)

data class Details(
    val name: String = "",
    val item: String = "",
    val value: String = "",
    val timeStamp: Instant = Instant.now()
)
