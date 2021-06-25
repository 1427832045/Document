package com.seer.srd.route

import com.seer.srd.eventbus.EventBus.onVehiclesDetailsChanged
import com.seer.srd.route.service.VehicleService
import com.seer.srd.util.mapper
import com.seer.srd.vehicle.driver.VehicleDriverManager.getVehicleControllerOrNull
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("com.seer.srd.vehicle")

fun getVehicleDetails(): Map<String, Map<*, *>> {
    val result: MutableMap<String, Map<*, *>> = HashMap()

    VehicleService.listVehicles().forEach { v ->
        val details = getVehicleDetailsByName(v.name)
        if (details != null) result[v.name] = details
    }
    return result
}

fun getVehicleDetailsByName(name: String): Map<*, *>? {
    val controller = getVehicleControllerOrNull(name)
    return if (controller != null) mapper.readValue(controller.details, MutableMap::class.java) else null
}

@Volatile
private var lastDetails: Map<String, Map<*, *>> = HashMap()

@Volatile
private var lastDetailsStr: String = ""

fun checkVehiclesDetails() {
    try {
        val newDetails = getVehicleDetails()
        // 为了简单，先暂时用 JSON 实现深度比较
        val newDetailsStr = mapper.writeValueAsString(newDetails)
        if (newDetailsStr == lastDetailsStr) return
        // LOG.debug("Vehicles details changed")
        lastDetails = newDetails
        lastDetailsStr = newDetailsStr
        onVehiclesDetailsChanged(newDetails.values.toList())
    } catch (e: Exception) {
        LOG.error("Check vehicles details", e)
    }
}