package com.seer.srd.weiyi

import com.mongodb.client.model.Sorts
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.RobotTask
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.collections.toList

data class SentTask(
    @BsonId val id: ObjectId = ObjectId(),
    val taskId: String = "",
    val def: String = "",
    val seqId: String = "",
    val priority: Int = 0,
    val createdOn: Instant = Instant.now(),
    var orderIds: List<String> = emptyList(),
    var vehicleId: String? = null,
    var finished: Boolean = false,
    var modifiedOn: Instant? = null,
    var remark: String = ""
)

object SentTaskManager {

    private val logger = LoggerFactory.getLogger(ExtHandlers::class.java)

    private val unfinishedSentTaskVar: MutableMap<String, String> = mutableMapOf()

    private val c = MongoDBManager.collection<SentTask>()

    private fun getSentTaskVarToStringById(st: SentTask): String {
        return "SentTask=${st.id} => var(finished=${st.finished}, orderId=${st.orderIds}, vehicleId=${st.vehicleId}, " +
            "modifiedOn=${st.modifiedOn}, remark=${st.remark})"
    }

    fun listUnfinishedSentTasksAscByCreatedOn(): List<SentTask> {
        return c.find(SentTask::finished eq false).sort(Sorts.ascending("createdOn")).toList()
    }

    fun getUnfinishedSentTaskById(id: String): SentTask {
        val st = c.find(SentTask::taskId eq id, SentTask::finished eq false).toList()
        return when (st.size) {
            0 -> throw BusinessError("no record sentTask=$id")
            1 -> st.first()
            else -> throw BusinessError("too many record sentTask=$id count=${st.size}")
        }
    }

    private fun replaceValues(newSentTask: SentTask): SentTask {
        val taskId = newSentTask.taskId
        val st = getUnfinishedSentTaskById(taskId)
        val newOrderIds = newSentTask.orderIds
        if (newOrderIds.isNotEmpty() && newOrderIds.toString() != st.orderIds.toString())
            st.orderIds = newSentTask.orderIds

        st.modifiedOn = newSentTask.modifiedOn
        if (!newSentTask.vehicleId.isNullOrBlank()) st.vehicleId = newSentTask.vehicleId
        if (newSentTask.remark != st.remark) st.remark = newSentTask.remark
        if (newSentTask.finished) st.finished = newSentTask.finished // 防止已结束任务被修改为未完成

        val contains = unfinishedSentTaskVar.containsKey(taskId)
        val oldVarString = unfinishedSentTaskVar[taskId]
        val newVarString = getSentTaskVarToStringById(st)
        val finished = st.finished

        if (!contains && !finished) {
//            logger.info("replace values to: $newVarString")
            unfinishedSentTaskVar[taskId] = newVarString
        } else if (oldVarString != newVarString) {
//            logger.info("replace values to: $newVarString")
            if (finished) unfinishedSentTaskVar.remove(taskId)
            else unfinishedSentTaskVar[taskId] = newVarString
        }
        return st
    }

    @Synchronized
    fun updateSentTask(newSentTask: SentTask) {
        val taskId = newSentTask.taskId
//        logger.info("update sentTask=$taskId information.")
        val st = replaceValues(newSentTask)
        val finished = st.finished
        if (finished) logger.info("mark sentTask=$taskId to finished.")

        c.updateOne(SentTask::taskId eq taskId,
            set(SentTask::finished setTo finished,
                SentTask::orderIds setTo st.orderIds,
                SentTask::vehicleId setTo st.vehicleId,
                SentTask::modifiedOn setTo st.modifiedOn,
                SentTask::remark setTo st.remark
            )
        )
    }

    fun insertNewSentTask(task: RobotTask) {
        val taskId = task.id
        val c = MongoDBManager.collection<SentTask>()
        val record = c.findOne(SentTask::taskId eq taskId)
        if (record != null)
            logger.error("executing task=[$taskId] has been recorded.")
        else {
            val st = SentTask(taskId = taskId, def = task.def, priority = task.priority,
                seqId = task.transports.first().seqId ?: "-")
            logger.info("record new sentTask success $st.")
            c.insertOne(st)
        }
    }
}