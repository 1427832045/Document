package com.seer.srd.eventlog

import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.http.WebSocketManager.broadcastByWebSocket
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import java.time.Instant

enum class EventLogLevel {
    Error, Info
}

enum class SystemEvent {
    AppBoot,
    UserSignIn,
    VehicleError, VehicleRestore,
    RobotTaskSuccess, RobotTaskFailed, RobotTaskCreated,
    RouteDeadlock,
    ForceStatStart,
    ForceStatEnd,
    Extra
}

data class SystemEventLog(
    @BsonId val id: ObjectId,
    val category: String,
    val level: EventLogLevel,
    val event: SystemEvent,
    val details: String,
    val createdOn: Instant
)

fun recordSystemEventLog(category: String, level: EventLogLevel, event: SystemEvent, details: String) {
    recordSystemEventLog(SystemEventLog(ObjectId(), category, level, event, details, Instant.now()))
}

private fun recordSystemEventLog(eventLog: SystemEventLog) {
    collection<SystemEventLog>().insertOne(eventLog)
    broadcastByWebSocket("system-event", eventLog)
}