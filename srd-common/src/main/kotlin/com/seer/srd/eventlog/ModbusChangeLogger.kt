package com.seer.srd.eventlog

import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.http.WebSocketManager.broadcastByWebSocket
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import java.time.Instant

data class ModbusReadLog(
    @BsonId val id: ObjectId,
    val createdOn: Instant,
    val host: String,
    val port: Int,
    val cmd: String,
    val target: String,
    val oldValues: String,
    val newValues: String,
    val remark: String
)

data class ModbusWriteLog(
    @BsonId val id: ObjectId,
    val createdOn: Instant,
    val host: String,
    val port: Int,
    val cmd: String,
    val target: String,
    val values: String,
    val remark: String
)

fun recordModbusReadLog(log: ModbusReadLog) {
    collection<ModbusReadLog>().insertOne(log)
    broadcastByWebSocket("ModbusReadChanged", log)
}

fun recordModbusWriteLog(log: ModbusWriteLog) {
    collection<ModbusWriteLog>().insertOne(log)
    broadcastByWebSocket("ModbusWriteChanged", log)
}
