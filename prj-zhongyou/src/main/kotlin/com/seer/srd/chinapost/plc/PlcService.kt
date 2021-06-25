package com.seer.srd.chinapost.plc

import com.seer.srd.Error404
import com.seer.srd.chinapost.CUSTOM_CONFIG
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object PlcService {

    private val logger = LoggerFactory.getLogger(PlcService::class.java)

    private val managers: MutableMap<String, PlcManager> = ConcurrentHashMap()

    fun init() {
        if (CUSTOM_CONFIG.plcDeviceList.isEmpty()) return
        for (plcConfig in CUSTOM_CONFIG.plcDeviceList) {
            val manager = PlcManager(plcConfig)
            managers[plcConfig.id] = manager
            logger.info("new plc device[${plcConfig.id}].")
        }
    }

    @Synchronized
    fun dispose() {
        managers.values.forEach {
            try {
                it.dispose()
            } catch (e: Exception) {
                logger.error("dispose plcDevice ${it.config.id}", e)
            }
        }
        managers.clear()
    }

    fun getPlcDeviceByName(name: String): PlcManager {
        return managers[name] ?: throw Error404("不存在PLC【$name】！")
    }
}