package com.seer.srd.siemensCd_1

import com.seer.srd.route.service.VehicleService
import com.seer.srd.vehicle.Vehicle
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Services {

  private val logger = LoggerFactory.getLogger(Services::class.java)

  val emptyTrayVehicles: Queue<String> = ConcurrentLinkedQueue()

  private val executorService = Executors.newScheduledThreadPool(1)

  fun init() {
    executorService.scheduleAtFixedRate(this::checkSend, 1, 1, TimeUnit.SECONDS)
  }

  @Synchronized
  private fun checkSend() {
    if (!CUSTOM_CONFIG.emptyTrayTaskTrigger) return
    val queue = emptyTrayVehicles
    val vs = VehicleService.listVehicles()
    vs.forEach { vehicle ->
      if (queue.contains(vehicle.name)) {
        val canNotSend = vehicle.allocations.any { it.toUpperCase().contains("SM") }
        if (canNotSend) {
          val removed = emptyTrayVehicles.remove(vehicle.name)
          if (removed) logger.debug("removed [${vehicle.name}] from empty tray task trigger")
          else logger.warn("remove [${vehicle.name}] failed from empty tray task trigger!!")
        }
      }
    }
  }


}