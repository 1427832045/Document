package com.seer.srd.vehicle.driver.io

import org.opentcs.drivers.vehicle.LoadHandlingDevice
import org.opentcs.drivers.vehicle.MovementCommand
import org.opentcs.drivers.vehicle.VehicleProcessModel

abstract class AdapterIO {

    abstract fun connectVehicle()

    abstract fun disconnectVehicle()

    abstract fun setVehiclePaused(paused: Boolean)

    abstract fun sendMovementCommand(cmd: MovementCommand)

    abstract fun sendMovementCommands(cmds: List<MovementCommand>)

    abstract fun sendClearAllMovementCommands()

    abstract fun sendSafeClearAllMovementCommands(movementId: String?)

}

class VehicleStatus(
    var energyLevel: Int = 0,
    var loadHandingDevices: List<LoadHandlingDevice> = emptyList(),
    var errorInfos: List<VehicleProcessModel.ErrorInfo>? = null,
    var position: String? = null,
    var state: String = "",
    var exeState: String = "",
    var blocked: Boolean = false,
    var relocStatus: Int = -1
) {
    override fun toString(): String {
        return "VehicleStatus(energyLevel=$energyLevel, loadHandingDevices=$loadHandingDevices, errorInfos=$errorInfos, position=$position, state='$state', exeState='$exeState', blocked=$blocked, relocStatus=$relocStatus)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VehicleStatus

        if (energyLevel != other.energyLevel) return false
        if (loadHandingDevices != other.loadHandingDevices) return false
        if (errorInfos != other.errorInfos) return false
        if (position != other.position) return false
        if (state != other.state) return false
        if (exeState != other.exeState) return false
        if (blocked != other.blocked) return false
        if (relocStatus != other.relocStatus) return false

        return true
    }

    override fun hashCode(): Int {
        var result = energyLevel
        result = 31 * result + loadHandingDevices.hashCode()
        result = 31 * result + (errorInfos?.hashCode() ?: 0)
        result = 31 * result + (position?.hashCode() ?: 0)
        result = 31 * result + state.hashCode()
        result = 31 * result + exeState.hashCode()
        result = 31 * result + blocked.hashCode()
        result = 31 * result + (relocStatus?.hashCode() ?: 0)
        return result
    }

}

typealias VehicleStatusCallback = (vs: VehicleStatus) -> Unit

typealias VehicleDetailsCallback = (details: String) -> Unit