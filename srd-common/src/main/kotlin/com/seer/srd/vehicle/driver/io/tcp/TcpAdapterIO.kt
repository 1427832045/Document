package com.seer.srd.vehicle.driver.io.tcp

import com.seer.srd.vehicle.driver.io.AdapterIO
import com.seer.srd.vehicle.driver.io.http.MovementCommandReq
import org.opentcs.drivers.vehicle.MovementCommand
import org.slf4j.LoggerFactory
private val LOG = LoggerFactory.getLogger("com.seer.srd.vehicle.driver.io")

class TcpAdapterIO(
    private val vehicleName: String
) : AdapterIO() {

    override fun connectVehicle() {
    }

    override fun disconnectVehicle() {
    }

    override fun setVehiclePaused(paused: Boolean) {
        write(Message("Pause", arguments = mapOf("pause" to paused)), this::write)
    }

    override fun sendMovementCommand(cmd: MovementCommand) {
        sendMovementCommands(listOf(cmd))
    }

    override fun sendMovementCommands(cmds: List<MovementCommand>) {
        val content = MovementCmdGroup(cmds.map { MovementCommandReq().toReq(it) })
        val message = Message("Movements", arguments = mapOf("cmds" to content))
        LOG.debug("$vehicleName TCP command message: $message")
        LOG.debug("$vehicleName TCP command details: ${content.commands}")
        write(message, this::write)
    }

    override fun sendClearAllMovementCommands() {
        write(Message("ClearMovements"), this::write)
    }

    override fun sendSafeClearAllMovementCommands(movementId: String?) {
        var taskId = movementId
        if (taskId.isNullOrBlank()) {
            taskId = ""
        }
        write(Message("SafeClearMovements", arguments = mapOf("task_id" to taskId)), this::write)
    }

    private fun write(bytes: ByteArray, flush: Boolean = false) {
        val processor = VehicleAdapterTcpServer.vehicleToSocketMap[vehicleName] ?: return
        val os = processor.socket.getOutputStream()
        os.write(bytes)
        if (flush) os.flush()
    }

}