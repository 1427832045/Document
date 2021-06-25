package com.seer.srd.device

import com.seer.srd.BusinessError
import com.seer.srd.CONFIG
import com.seer.srd.Error400
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object ZoneService {
    
    private val logger = LoggerFactory.getLogger(ZoneService::class.java)
    
    private val zones: MutableMap<String, ZoneManager> = ConcurrentHashMap()

    private var involvedSystemIds: MutableMap<String, String> = HashMap()

    fun init() {
        CONFIG.zones.forEach { (name, config) ->
            zones[name] = ZoneManager(name, config)
            config.involvedSystems.forEach { involvedSystemIds[it.value] = it.key }
        }

        logger.debug(involvedSystemIds.toString())
    }
    
    @Synchronized
    fun dispose() {
        zones.forEach { (name, zone) ->
            try {
                zone.dispose()
            } catch (e: Exception) {
                logger.error("dispose zone $name", e)
            }
        }
        zones.clear()
    }
    
    fun listZones(): List<ZoneManager> {
        return zones.values.toList()
    }
    
    fun getZoneByName(zoneName: String): ZoneManager {
        return zones[zoneName] ?: throw BusinessError("No such zone $zoneName")
    }

    fun parseSystemIdByIp(systemId: String?, remoteAddr: String): String {
        return if (!systemId.isNullOrBlank()) {
            systemId
        } else {
            val ip = if (remoteAddr in listOf("0:0:0:0:0:0:0:1", "0:0:0:0:127:0:0:1")) "127.0.0.1" else remoteAddr
            return involvedSystemIds[ip] ?: throw Error400("NoSystemId", "Missing SystemId")
        }
    }
    
}