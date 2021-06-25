package com.seer.srd.vehicle

import com.fasterxml.jackson.databind.JsonNode
import com.seer.srd.BusinessError
import com.seer.srd.http.WebSocketManager.broadcastByWebSocket
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.time.Instant

object VehicleUpgradeManager {
    
    private val logger = LoggerFactory.getLogger(VehicleUpgradeManager::class.java)
    
    @Volatile
    private var latestTask: VehicleUpgradeTask? = null
    
    @Synchronized
    fun getLatestTask(): VehicleUpgradeTask? {
        return latestTask
    }
    
    @Synchronized
    fun start(filePath: String, vehicles: List<String>, lang: String) {
        val lastTask = latestTask
        if (lastTask != null && !lastTask.finished) throw BusinessError("Last task not finished")
        
        val taskId = ObjectId().toHexString()
        
        latestTask = VehicleUpgradeTask(taskId, vehicles.toSet())
        
        val notice = VehicleUpgradeNotice(taskId, filePath, vehicles, lang)
        broadcastByWebSocket("VehicleUpgradeStart", notice)
    }
    
    @Synchronized
    fun abort() {
        latestTask = null
    }
    
    @Synchronized
    fun onUpgrading(content: JsonNode) {
        val taskId = content["taskId"].asText()
        val lastTask = latestTask
        if (lastTask == null || lastTask.finished || lastTask.id != taskId) {
            logger.warn("Got vehicle upgrade progress, but latest upgrade is ${latestTask?.id}.${lastTask?.finished}")
            return
        }
        
        val vehicleName = content["vehicleName"].asText()
        val done = content["done"].asBoolean()
        val fail = content["fail"].asBoolean()
        val message = content["message"].asText()
        val progressPercent = content["progressPercent"].asInt()
        
        val progresses = ArrayList(lastTask.progresses)
        progresses += VehicleUpgradeProgress(vehicleName, done, fail, message, progressPercent)
        
        var newTask = lastTask.copy(progresses = progresses)
        
        if (done) {
            logger.info("Vehicle upgrade done id=${lastTask.id} v=$vehicleName fail=$fail")
            val finishedVehicles = HashSet(lastTask.finishedVehicles)
            finishedVehicles.add(vehicleName)
            newTask = newTask.copy(finishedVehicles = finishedVehicles)
            if (finishedVehicles.size == newTask.allVehicles.size) {
                logger.info("All vehicle upgrades done id=${newTask.id}")
                newTask = newTask.copy(finished = true, finishedOn = Instant.now())
            }
        }
        
        latestTask = newTask
        
        broadcastByWebSocket("VehicleUpgradeProgress", newTask) // 通知页面用
    }
}

data class VehicleUpgradeNotice(
    val taskId: String,
    val downloadPath: String,
    val vehicles: List<String>,
    val lang: String,
    val createdOn: Instant = Instant.now()
)

data class VehicleUpgradeProgress(
    val vehicleName: String,
    val done: Boolean,
    val fail: Boolean,
    val message: String,
    val progressPercent: Int
)

data class VehicleUpgradeTask(
    val id: String,
    val allVehicles: Set<String>,
    val finishedVehicles: Set<String> = emptySet(),
    val startedOn: Instant = Instant.now(),
    val finished: Boolean = false,
    val finishedOn: Instant? = null,
    val progresses: List<VehicleUpgradeProgress> = emptyList()
)