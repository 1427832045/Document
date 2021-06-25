package org.opentcs.drivers.vehicle.management

import com.seer.srd.vehicle.Vehicle
import org.opentcs.drivers.vehicle.VehicleProcessModel
import java.io.Serializable

/**
 * A serializable representation of a [VehicleProcessModel].
 */
class VehicleProcessModelTO(vpm: VehicleProcessModel) : Serializable {

    val vehicleName = vpm.getName()

    // Whether the comm adapter is currently enabled.
    val isCommAdapterEnabled = vpm.isCommAdapterEnabled

    // The name of the vehicle's current position.
    val vehiclePosition = vpm.vehiclePosition

    // The percise position of the vehicle.
    val precisePosition = vpm.precisePosition

    // The vehicle's orientation angle.
    val orientationAngle = vpm.orientationAngle

    // The vehicle's energy level.
    val energyLevel = vpm.energyLevel

    // A list of load handling devices attached to the vehicle.
    val loadHandlingDevices = vpm.loadHandlingDevices

    val vehicleState: Vehicle.State = vpm.state
}