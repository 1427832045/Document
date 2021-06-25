package com.seer.srd.vehicle.driver.io.tcp

import com.fasterxml.jackson.databind.JsonNode
import com.seer.srd.io.aioTcp.AioTcpHelper
import com.seer.srd.util.mapper
import com.seer.srd.vehicle.driver.VehicleDriverManager
import com.seer.srd.vehicle.driver.io.VehicleStatus
import kotlinx.coroutines.sync.Mutex
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.ClosedChannelException
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.TimeUnit

class AioTcpServerConnection(val channel: AsynchronousSocketChannel) {
    private val logger = LoggerFactory.getLogger(AioTcpServerConnection::class.java)
    var vehicleName: String? = null
    private val logHead = "[${channel.remoteAddress}]"
    private val lock = Mutex() // TODO 哪里需要加锁？
    private var pkgCount: Long = 0
    
    // TODO @Volatile?
    private var initialized = true // 创造对象时状态为已连接
    
    fun close() {
        initialized = false
        logger.info("{} closing channel", logHead)
        logger.info("{}", channel)
        try {
            channel.close()
        } catch (e: IOException) {
            logger.error("close {}", e)
        }
    }
    
    suspend fun listen() {
        while (initialized) {
            try {
                val msgBuffer = try {
                    findPkgHead()
                    
                    val length = findPkgLength()
                    
                    findMessage(length)
                } catch (e: Exception) {
                    // TODO 在哪里close channel?
                    throw e
                }
//                logger.info("{} Found body", logHead)
                
                onMessage(msgBuffer.array())
                pkgCount += 1
            } catch (e: ClosedChannelException) {
                return
            }
        }
    }
    
    private suspend fun findPkgHead() {
//        logger.info("{} waiting for package {}", logHead, pkgCount + 1)
        val buffer = ByteBuffer.allocate(tcpPkgHead.size)
        while (true) { // 在执行这个函数的时候closeChannel会报错
            buffer.rewind()
            AioTcpHelper.readAll(channel, buffer, 30, TimeUnit.SECONDS)
            if (Arrays.equals(buffer.array(), tcpPkgHead)) break
        }
//        logger.info("{} found package {}", logHead, pkgCount + 1)
    }
    
    private suspend fun findPkgLength(): Short {
        val buffer = ByteBuffer.allocate(pkgLenWidth) // buffer's default order is BIG_ENDIAN
        AioTcpHelper.readAll(channel, buffer, 30, TimeUnit.SECONDS)
        buffer.flip()
        return buffer.short
    }
    
    private suspend fun findMessage(length: Short): ByteBuffer {
        val buffer = ByteBuffer.allocate(length.toInt())
        AioTcpHelper.readAll(channel, buffer, 30, TimeUnit.SECONDS)
        return buffer
    }
    
    // TODO onMessage放这里合适吗？
    private fun onMessage(msgBytes: ByteArray) {
        val msgString = String(msgBytes, StandardCharsets.UTF_8)
        val json = mapper.readTree(msgString)
        val msgType = json.get("type").asText()
        when (msgType) {
            "SignIn" -> onSignIn(json)
            "StatusReport" -> onStatusReport(json)
            "AckMovement" -> onAckMovement(json)
            "Details" -> onDetails(json)
        }
    }
    
    private fun onSignIn(json: JsonNode) {
        val vehicle = json["arguments"]["vehicle"].asText()
        vehicleName = vehicle
//        logger.info("{} sign in, vehicleName={}", logHead, vehicleName)
        VehicleAdapterAioTcpServer.setVehicleConnection(vehicle, this)
    }
    
    private fun onStatusReport(json: JsonNode) {
        val vehicle = json["arguments"]["vehicle"].asText()
        if (vehicle != vehicleName) return
        val node = json["arguments"]["report"]
        val status = mapper.treeToValue(node, VehicleStatus::class.java)
        logger.debug(
            "{} vehicleName: {} Receive vehicle status: pos={}, state={}, energy={}",
            logHead, vehicleName, status.position, status.state, status.energyLevel
        )
        VehicleDriverManager.getVehicleCommAdapterOrNull(vehicle)?.onVehicleStatus(status)
    }
    
    private fun onAckMovement(json: JsonNode) {
        val vehicle = json["arguments"]["vehicle"].asText()
        if (vehicle != vehicleName) return
        VehicleDriverManager.getVehicleCommAdapterOrNull(vehicle)?.onAckMovementCommandOfFlowNo()
    }
    
    private fun onDetails(json: JsonNode) {
        val vehicle = json["arguments"]["vehicle_id"].asText()
        if (vehicle != vehicleName) return
        val details = json["arguments"].toString()
        VehicleDriverManager.getVehicleCommAdapterOrNull(vehicle)?.onVehicleDetails(details)
    }
    
}
