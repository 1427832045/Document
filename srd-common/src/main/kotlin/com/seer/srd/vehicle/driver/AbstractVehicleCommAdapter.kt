package com.seer.srd.vehicle.driver

import com.seer.srd.route.routeConfig
import com.seer.srd.vehicle.Vehicle
import com.seer.srd.vehicle.driver.io.VehicleStatus
import org.opentcs.components.Lifecycle
import org.opentcs.drivers.vehicle.AdapterCommand
import org.opentcs.drivers.vehicle.MovementCommand
import org.opentcs.drivers.vehicle.VehicleProcessModel
import org.opentcs.util.ExplainedBoolean
import java.beans.PropertyChangeListener
import java.util.*

abstract class AbstractVehicleCommAdapter(private val vehicle: Vehicle) : PropertyChangeListener, Lifecycle {
    abstract val commandQueueCapacity: Int
    abstract val processModel: VehicleProcessModel
    
    abstract fun enable()
    abstract fun disable()
    abstract fun lock(): Any
    abstract fun clearCommandQueue()
    abstract fun safeClearCommandQueue()
    abstract fun canProcess(): ExplainedBoolean
    abstract fun processMessage(message: Any)
    abstract fun execute(command: AdapterCommand)
    abstract fun appendToCommandQueue(command: MovementCommand): Boolean
    abstract fun onVehicleStatus(req: VehicleStatus)
    abstract fun onVehicleDetails(req: String)
    abstract fun setVehiclePaused(b: Boolean)
    abstract fun onAckMovementCommandOfFlowNo()
    abstract fun acquireVehicle(nickName: String = routeConfig.srdNickName): Boolean
    abstract fun disownVehicle():Boolean
    abstract fun clearAllErrors():Boolean
    abstract fun getSentQueue(): Queue<MovementCommand>
    abstract fun getCommandQueue(): Queue<MovementCommand>
    abstract fun canResend(currentPosition: String?): Boolean
}
