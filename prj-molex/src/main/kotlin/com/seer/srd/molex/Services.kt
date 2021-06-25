package com.seer.srd.molex

import com.mongodb.client.model.Indexes
import com.mongodb.client.model.UpdateOptions
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.eventlog.StoreSiteChange
import com.seer.srd.molex.SiteAreaService.getAreaModBusById
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskService
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.robottask.RobotTransportState
import com.seer.srd.storesite.StoreSite
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.Exception
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Services {

  private val logger = LoggerFactory.getLogger(Services::class.java)

  private val executor = Executors.newScheduledThreadPool(1)

  private var recordOn: Instant? = null

  @Volatile
  private var checked = false

  // 存放已下发给调度的任务队列
  // 可添加：容量小于最大容量
  // 取出：任务完成
  val sentTaskList: Queue<String> = ConcurrentLinkedQueue()

  // 保存未下发的栈板列任务
  val unSentPalletMap: MutableMap<String, String> = ConcurrentHashMap()

  fun initSentTaskQueue() {
    val c = MongoDBManager.collection<RobotTask>().apply {
      this.createIndex(Indexes.ascending("state"))
    }
    val tasks = c.find(RobotTask::state lt RobotTaskState.Success).toList()
    tasks.forEach {
      if (sentTaskList.size < CUSTOM_CONFIG.taskSentListCapacity) {
        val transport = it.transports[0]
        if (transport.state > RobotTransportState.Created) sentTaskList.add(it.id)
      }
//      if (it.def == CUSTOM_CONFIG.palletDef && it.transports[0].state < RobotTransportState.Sent) {
//        unSentPalletMap[it.id] = it.persistedVariables["fromType"] as String
//      }
    }
  }

  init {
    executor.scheduleAtFixedRate(this::deleteSiteChangedLog, 0, 1, TimeUnit.DAYS)
  }

  private fun deleteSiteChangedLog() {
    executor.submit {
      if (!checked) {
        checked = true
        val item = MongoDBManager.collection<StoreSiteChangedDate>().findOne()
        val c = MongoDBManager.collection<StoreSiteChangedDate>()
        val now = Instant.now()
        if (item?.recordOn == null)
          c.updateOne(
              StoreSiteChangedDate::id exists true,
              set(StoreSiteChangedDate::recordOn setTo now),
              UpdateOptions().upsert(true)
          )
        recordOn = c.findOne()?.recordOn
        MongoDBManager.collection<StoreSiteChange>().deleteMany(StoreSiteChange::timestamp lt now.minus(60, ChronoUnit.DAYS))
      }
    }
  }

  fun getFromSites(): List<String> {
    return MongoDBManager.collection<StoreSite>().find().toList().filter {
      !it.id.contains("T-")
    }.map { it.id }
  }

  fun checkOtherArea(curFromType: String, curToType: String, unSentTasks: List<RobotTask>): Boolean {
    var intersectionSize = 0
    unSentTasks.forEach {
      val fromType = it.persistedVariables["fromType"] as String
      val toType = it.persistedVariables["toType"] as String
      val intersection = listOf(fromType, toType).intersect(listOf(curFromType, curToType))
      if (intersection.isNotEmpty()) intersectionSize++
    }
    if (intersectionSize == unSentTasks.size) return true
    return false
  }

  fun existedNonLastPalletTask(unfinishedPalletTasks: List<RobotTask>): Boolean {
   for (task in unfinishedPalletTasks) {
     if (task.persistedVariables["lastPalletTask"] == false) return true
   }
    return false
  }

  fun getToSites(): List<String> {
    return MongoDBManager.collection<StoreSite>().find().toList().map { it.id }
  }

  fun getCabinetBySiteId(siteId: String): Cabinet? {
    CUSTOM_CONFIG.areaToCabinet.forEach {
      val cabinet = it.value
      if (cabinet.siteIdToAddress.containsKey(siteId))
        return it.value
    }
    return null
  }

}

data class SiteFireTask(
    @BsonId val id: ObjectId = ObjectId(),
    val siteId: String = "",
    val fireTask: MutableList<String> = mutableListOf()
)
data class StoreSiteChangedDate(
    @BsonId val id: ObjectId = ObjectId(),
    val recordOn: Instant? = null
)


data class TaskReq(
    val workStation: String = "",
    val workType: String = "",
    val params: LinkedHashMap<String, String> = LinkedHashMap()
)

class TaskSend (
  var taskId: String = "",
  var immediate: Boolean? = null,
  var canSend: Boolean? = null
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as TaskSend

    if (taskId != other.taskId) return false

    return true
  }

  override fun hashCode(): Int {
    return taskId.hashCode()
  }

}