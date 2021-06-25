package com.seer.srd.vehicle.driver.io.tcp

import com.seer.srd.io.aioTcp.AioTcpHelper
import com.seer.srd.util.mapper
import com.seer.srd.vehicle.driver.io.AdapterIO
import com.seer.srd.vehicle.driver.io.http.MovementCommandReq
import kotlinx.coroutines.runBlocking
import org.opentcs.drivers.vehicle.MovementCommand
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.charset.StandardCharsets

class AioTcpAdapterIO(private val vehicleName: String) : AdapterIO() {
    private val logger = LoggerFactory.getLogger(AioTcpAdapterIO::class.java)
    private val logHead = "[$vehicleName]"
    
    override fun connectVehicle() {
    }
    
    override fun disconnectVehicle() {
    }
    
    override fun setVehiclePaused(paused: Boolean) {
        write(Message("Pause", arguments = mapOf("pause" to paused)))
        logger.info("{} paused", logHead)
    }
    
    override fun sendMovementCommand(cmd: MovementCommand) {
        logger.info("{} sending movement command", logHead)
        sendMovementCommands(listOf(cmd))
    }
    
    override fun sendMovementCommands(cmds: List<MovementCommand>) {
        val content = MovementCmdGroup(cmds.map { MovementCommandReq().toReq(it) })
        logger.debug("{} TCP command message: {} ", logHead, Message("Movements", arguments = mapOf("cmds" to content)))
        write(Message("Movements", arguments = mapOf("cmds" to content)))
    }
    
    override fun sendClearAllMovementCommands() {
        write(Message("ClearMovements"))
    }

    override fun sendSafeClearAllMovementCommands(movementId: String?) {
        var taskId = movementId
        if (taskId.isNullOrBlank()) {
            taskId = ""
        }
        write(Message("SafeClearMovements", arguments = mapOf("task_id" to taskId)))
    }
    
    private fun write(msg: Message) {
        val channel = VehicleAdapterAioTcpServer.getChannel(vehicleName)
        if (channel == null) {
            logger.info("No channel for vehicle {}, cannot write", vehicleName)
            return
        }
        val msgBytes = mapper.writeValueAsString(msg).toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(tcpPkgHead.size + pkgLenWidth + msgBytes.size)
        
        buffer.put(tcpPkgHead)
        buffer.putShort(msgBytes.size.toShort())
        buffer.put(msgBytes)
        buffer.flip()
        
        try {
            runBlocking {
                AioTcpHelper.writeAll(channel, buffer)
            }
        } catch (e: ClosedChannelException) {
            logger.info("{} channel closed, failed to write", logHead)
            return
        }
    }
}