package com.seer.srd.siemensSH.customStoreSite

import com.seer.srd.BusinessError
import com.seer.srd.siemensSH.common.CUSTOM_CONFIG
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object CustomStoreSiteService {

    private val logger = LoggerFactory.getLogger(CustomStoreSiteService::class.java)

    private val customStoreSiteManagers: MutableMap<String, CustomStoreSiteManager> = ConcurrentHashMap()

    fun init() {
        CUSTOM_CONFIG.plcDevices.forEach { (name, config) ->
            customStoreSiteManagers[name] = CustomStoreSiteManager(name, config)
        }
    }

    fun listStoreSiteManagers(): MutableMap<String, CustomStoreSiteManager> {
        return  customStoreSiteManagers
    }

    fun getStoreSiteByName(siteId: String): CustomStoreSiteManager {
        return customStoreSiteManagers[siteId] ?: throw BusinessError("No such manager[$siteId]")
    }
}