package com.seer.srd.eventlog

import com.seer.srd.db.MongoDBManager.collection
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import java.time.Instant

data class UserOperationLog(
    @BsonId val id: ObjectId = ObjectId(),
    val userId: ObjectId = ObjectId(),
    val operation: String = "",
    val details: String = "",
    val createdOn: Instant = Instant.now()
)

fun recordUserOperationLog(operationLog: UserOperationLog) {
    collection<UserOperationLog>().insertOne(operationLog)
}
