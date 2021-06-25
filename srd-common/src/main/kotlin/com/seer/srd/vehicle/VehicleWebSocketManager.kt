package com.seer.srd.vehicle

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

object VehicleWebSocketManager {

    private val logger = LoggerFactory.getLogger(VehicleWebSocketManager::class.java)

    private val channels: MutableMap<String, VehicleWebSocketChannel> = ConcurrentHashMap()

    fun listVehicleChannels(): List<VehicleWebSocketChannel> {
        return channels.values.toList()
    }

    fun onSignIn(content: JsonNode, sessionId: String) {
        val vehicleName = content["vehicleName"].asText()
        val vehicleIpAddress = content["vehicleIpAddress"].asText()
        val versionsDescription = content["versionsDescription"].asText()
        logger.info("vehicle sign in on web socket $vehicleName")
        val channel = VehicleWebSocketChannel(vehicleName, sessionId, vehicleIpAddress, versionsDescription)
        channels[vehicleName] = channel
    }

    @Synchronized
    fun onSocketClose(sessionId: String) {
        val keys = channels.entries.filter { it.value.sessionId == sessionId }.map { it.key }
        keys.forEach { channels.remove(it) }
    }

}

data class VehicleWebSocketChannel(
    val name: String,
    val sessionId: String,
    val vehicleIpAddress: String,
    val versionsDescription: String,
    val createdOn: Instant = Instant.now()
)