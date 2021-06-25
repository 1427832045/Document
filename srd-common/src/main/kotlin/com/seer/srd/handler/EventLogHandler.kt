package com.seer.srd.handler

import com.mongodb.client.model.Sorts.descending
import com.mongodb.client.model.Sorts.orderBy
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.domain.PagingResult
import com.seer.srd.eventlog.*
import com.seer.srd.http.ensureRequestUserRolePermission
import com.seer.srd.http.getPageNo
import com.seer.srd.http.getPageSize
import com.seer.srd.user.UserManager.getUserById
import io.javalin.http.Context
import org.bson.types.ObjectId
import java.time.Instant

fun handleListSystemEvents(ctx: Context) {
    val pageNo = getPageNo(ctx)
    val pageSize = getPageSize(ctx)

    val c = collection<SystemEventLog>()
    val total = c.countDocuments()
    val page = c.find()
        .limit(pageSize).skip((pageNo - 1) * pageSize).sort(orderBy(descending("createdOn")))
        .toList()

    ctx.json(PagingResult(total, page, pageNo, pageSize))
}

data class RecordUserOperationReq(
    val operation: String = "",
    val details: String = ""
)

data class UserOperationLogForRead(
    val id: ObjectId = ObjectId(),
    val userId: ObjectId = ObjectId(),
    val operation: String = "",
    val details: String = "",
    val createdOn: Instant = Instant.now(),
    val operatorUsername: String = ""
)

// 记录用户关键操作
fun handleRecordUserOperation(ctx: Context) {
    val urp = ensureRequestUserRolePermission(ctx)

    val req = ctx.bodyAsClass(RecordUserOperationReq::class.java)

    recordUserOperationLog(UserOperationLog(ObjectId(), urp.user.id, req.operation, req.details))

    ctx.status(201)
}

fun handleListUserOperations(ctx: Context) {
    val pageNo = getPageNo(ctx)
    val pageSize = getPageSize(ctx)

    val c = collection<UserOperationLog>()
    val total = c.countDocuments()
    val page = c.find()
        .limit(pageSize).skip((pageNo - 1) * pageSize).sort(orderBy(descending("createdOn"))).toList()
        .map {
            UserOperationLogForRead(
                it.id,
                it.userId,
                it.operation,
                it.details,
                it.createdOn,
                getUserById(it.userId)?.username ?: ""
            )
        }
    ctx.json(PagingResult(total, page, pageNo, pageSize))
}

fun handleListModbusReadLogs(ctx: Context) {
    val pageNo = getPageNo(ctx)
    val pageSize = getPageSize(ctx)

    val c = collection<ModbusReadLog>()
    val total = c.countDocuments()
    val page = c.find().limit(pageSize).skip((pageNo - 1) * pageSize).sort(orderBy(descending("createdOn"))).toList()
    ctx.json(PagingResult(total, page, pageNo, pageSize))
}

fun handleListModbusWriteLogs(ctx: Context) {
    val pageNo = getPageNo(ctx)
    val pageSize = getPageSize(ctx)

    val c = collection<ModbusWriteLog>()
    val total = c.countDocuments()
    val page = c.find().limit(pageSize).skip((pageNo - 1) * pageSize).sort(orderBy(descending("createdOn"))).toList()
    ctx.json(PagingResult(total, page, pageNo, pageSize))
}