package com.seer.srd.robottask

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.ReplaceOptions
import com.seer.srd.BusinessError
import com.seer.srd.Error400
import com.seer.srd.I18N.locale
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.domain.Property
import com.seer.srd.robottask.RobotTaskService.saveNewRobotTask
import com.seer.srd.user.HumanUser
import com.seer.srd.util.mapper
import org.apache.commons.lang3.StringUtils
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.contains
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.litote.kmongo.ne
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@JsonIgnoreProperties(ignoreUnknown = true)
class ManualTask(
    @BsonId var id: ObjectId = ObjectId(),
    var name: String = "",
    var taskId: String = "",
    var taskIdAutoInc: Boolean = false,
    var category: String = "",
    var intendedVehicle: String = "",
    var intendedUsers: MutableList<String> = ArrayList(),
    var deadlineDelta: Int = 0,
    var deadlineSign: Int = 0,
    var destinations: MutableList<ManualTaskDestination> = ArrayList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
class ManualTaskDestination(
    var locationName: String = "",
    var operation: String = "",
    var properties: MutableList<Property> = ArrayList()
)

private var taskIdSuffixCounter = 0

fun listManualTasks(): List<ManualTask> {
    return collection<ManualTask>().find().toList()
}

fun listManualTasksByUsername(username: String): List<ManualTask> {
    return collection<ManualTask>().find(ManualTask::intendedUsers contains(username)).toList()
}

fun updateManualTask(mt: ManualTask, lang: String) {
    checkNameDuplicated(mt, lang)

    collection<ManualTask>().replaceOne(ManualTask::id eq mt.id, mt, ReplaceOptions().upsert(true))
}

fun removeManualTask(id: ObjectId) {
    collection<ManualTask>().deleteOne(ManualTask::id eq id)
}

private fun getManualTaskById(id: ObjectId): ManualTask? {
    return collection<ManualTask>().findOne(ManualTask::id eq id)
}

fun executeManualTask(id: ObjectId) {
    val mt = getManualTaskById(id) ?: return
    saveNewRobotTask(toTaskInstance(mt))
}

fun executeTempManualTask(mt: ManualTask) {
    saveNewRobotTask(toTaskInstance(mt))
}

private fun toTaskInstance(mt: ManualTask): RobotTask {
    val task = RobotTask(id = buildTaskId(mt))
    task.transports = listOf(RobotTransport(
        taskId = task.id,
        deadline = buildDeadline(mt),
        category = mt.category,
        intendedRobot = mt.intendedVehicle,
        stages = mt.destinations.map { dst ->
            RobotStage(
                location = dst.locationName,
                operation = dst.operation,
                properties = mapper.writeValueAsString(dst.properties)
            )
        }
    ))
    return task
}

private fun checkNameDuplicated(mt: ManualTask, lang: String) {
    val c = collection<ManualTask>()
    if (c.findOne(and(ManualTask::name eq mt.name, ManualTask::id ne mt.id)) != null)
        throw Error400("ManualTaskNameDuplicated", locale("ManualTaskNameDuplicated", lang))
}

private val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmSS")

private fun buildTaskId(mt: ManualTask): String {
    return if (mt.taskIdAutoInc) {
        taskIdSuffixCounter = (taskIdSuffixCounter + 1) % 1000
        mt.taskId + formatter.format(LocalDateTime.now()) + taskIdSuffixCounter.toString().padStart(3, '0')
    } else {
        StringUtils.firstNonBlank(mt.taskId, ObjectId().toHexString())
    }
}

private fun buildDeadline(mt: ManualTask): Instant? {
    return if (mt.deadlineDelta > 0) {
        val sign = if (mt.deadlineSign > 0) 1 else -1
        val seconds = mt.deadlineDelta * sign
        Instant.now().plusSeconds(seconds.toLong())
    } else {
        null
    }
}
