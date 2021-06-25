package com.seer.srd.device

import com.seer.srd.BusinessError
import com.seer.srd.CONFIG
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object DoorService {
    
    private val logger = LoggerFactory.getLogger(DoorService::class.java)
    
    private val doors: MutableMap<String, DoorManager> = ConcurrentHashMap()
    
    fun init() {
        CONFIG.doors.forEach { (name, config) ->
            doors[name] = DoorManager(name, config)
        }
    }
    
    @Synchronized
    fun dispose() {
        doors.forEach { (name, door) ->
            try {
                door.dispose()
            } catch (e: Exception) {
                logger.error("dispose door $name", e)
            }
        }
        doors.clear()
    }
    
    fun getDoorByName(doorName: String): DoorManager {
        return doors[doorName] ?: throw BusinessError("No such door $doorName")
    }
    
    fun listStatusDO(): List<DoorStatusDO> {
        return doors.values.map { it.getDoorStatusDO() }
    }
    
}

