package com.seer.srd.device.lift

import com.seer.srd.CONFIG
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object LiftService {

    val managers: MutableMap<String, AbstractLiftManager> = ConcurrentHashMap()

    private val logger = LoggerFactory.getLogger(LiftService::class.java)

    fun init() {
        if (CONFIG.lifts.isEmpty()) return
        for (liftConfig in CONFIG.lifts) {
            val manager = when(liftConfig.mode) {
                IOMode.ModbusTcp -> LiftManagerModbusTcp(liftConfig)
                IOMode.ModbusTcpSiemensCd -> LiftManagerModbusTcpCustom(liftConfig)
                IOMode.ModbusTcpSiemensCdV2 -> LiftManagerModbusTcpCustomV2(liftConfig)
                else -> LiftManager(liftConfig)
            }
            managers[liftConfig.name] = manager
        }
    }

    @Synchronized
    fun dispose() {
        managers.values.forEach {
            try {
                it.dispose()
            } catch (e: Exception) {
                logger.error("dispose lift ${it.liftConfig.name}", e)
            }
        }
        managers.clear()
    }

    fun listLiftsModels(): List<LiftModel> {
        return managers.values.map { it.getLiftModel() }.toList()
    }

}