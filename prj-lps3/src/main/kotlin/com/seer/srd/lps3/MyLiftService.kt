package com.seer.srd.lps3

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object MyLiftService {

  val managers: MutableMap<String, LiftManager> = ConcurrentHashMap()

  private val logger = LoggerFactory.getLogger(MyLiftService::class.java)

  fun init() {
    if (CUSTOM_CONFIG.myLifts.isEmpty()) return
    for (liftConfig in CUSTOM_CONFIG.myLifts) {
      val manager = LiftManager(liftConfig)
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