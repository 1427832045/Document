package com.seer.srd.vehicle.driver.io.tcp

import com.fasterxml.jackson.databind.JsonNode
import com.seer.srd.io.tcp.ByteArrayBuffer
import com.seer.srd.io.tcp.InputStreamToPkg
import com.seer.srd.io.tcp.PkgExtractor
import com.seer.srd.util.mapper
import com.seer.srd.vehicle.driver.VehicleDriverManager
import com.seer.srd.vehicle.driver.io.VehicleStatus
import org.slf4j.LoggerFactory
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class VehicleTcpProcessor(val socket: Socket) {

    private val logger = LoggerFactory.getLogger(VehicleTcpProcessor::class.java)

    private var vehicleName: String? = null

    private val pkgExtractor = PkgExtractor(tcpPkgHead, this::parsePkgLen, this::onPkg)

    private val inputStreamToPkg =
        InputStreamToPkg(socket.getInputStream(), 1500, this.pkgExtractor, this::onError)

    init {
        inputStreamToPkg.start()
    }

    fun dispose() {
        inputStreamToPkg.stop()
        socket.close()
    }

    private fun parsePkgLen(buffer: ByteArrayBuffer): Int {
        if (buffer.validEndIndex - buffer.validStartIndex < tcpPkgHead.size + pkgLenWidth) return -1
        val byteBuffer = ByteBuffer.wrap(buffer.buffer, buffer.validStartIndex + tcpPkgHead.size, pkgLenWidth)
        byteBuffer.order(ByteOrder.BIG_ENDIAN)
        return byteBuffer.short.toInt() + tcpPkgHead.size + pkgLenWidth // 要包含头
    }

    private fun onPkg(buffer: ByteArrayBuffer, len: Int) {
        val str = String(
            buffer.buffer,
            buffer.validStartIndex + tcpPkgHead.size + pkgLenWidth,
            len - tcpPkgHead.size - pkgLenWidth,
            StandardCharsets.UTF_8
        )
        val json = mapper.readTree(str)
        val type = json.get("type").asText()
        // logger.debug("$type: $str")
        when (type) {
            "SignIn" -> onSignIn(json)
            "StatusReport" -> onStatusReport(json)
            "AckMovement" -> onAckMovement(json)
            "Details" -> onDetails(json)
        }
    }

    private fun onError(e: Exception) {
        logger.error("", e)
    }

    private fun onSignIn(json: JsonNode) {
        val vehicle = json["arguments"]["vehicle"].asText()
        vehicleName = vehicle
        VehicleAdapterTcpServer.vehicleToSocketMap[vehicle] = this
    }

    private fun onStatusReport(json: JsonNode) {
        val vehicle = json["arguments"]["vehicle"].asText()
        if (vehicle != vehicleName) return
        val node = json["arguments"]["report"]
        val status = mapper.treeToValue(node, VehicleStatus::class.java)
        logger.debug("vehicleName: " + vehicleName + " Receive vehicle status: pos=" +
                status.position + ", state=" + status.state + ", energy=" + status.energyLevel)
        VehicleDriverManager.getVehicleCommAdapterOrNull(vehicle)?.onVehicleStatus(status)
    }

    private fun onAckMovement(json: JsonNode) {
        val vehicle = json["arguments"]["vehicle"].asText()
        if (vehicle != vehicleName) return
        // val ackFlowNo = json["arguments"]["ackFlowNo"].asText()
        VehicleDriverManager.getVehicleCommAdapterOrNull(vehicle)?.onAckMovementCommandOfFlowNo()
    }

    private fun onDetails(json: JsonNode) {
        val vehicle = json["arguments"]["vehicle_id"].asText()
        if (vehicle != vehicleName) return
        val details = json["arguments"].toString()
        VehicleDriverManager.getVehicleCommAdapterOrNull(vehicle)?.onVehicleDetails(details)
    }

}