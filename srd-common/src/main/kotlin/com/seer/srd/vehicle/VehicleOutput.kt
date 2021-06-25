package com.seer.srd.vehicle

import com.seer.srd.route.service.TransportOrderService
import com.seer.srd.vehicle.driver.VehicleDriverManager.getVehicleControllerOrNull
import org.opentcs.data.order.DriveOrder
import org.opentcs.drivers.vehicle.LoadHandlingDevice
import org.opentcs.drivers.vehicle.VehicleProcessModel

class VehicleOutput(vehicle: Vehicle) {
    val name: String = vehicle.name
    val properties: Map<String, String> = vehicle.properties
    val state = vehicle.state
    val categories: Set<String> = vehicle.processableCategories
    val energyLevelGood = vehicle.energyLevelGood
    val energyLevelCritical = vehicle.energyLevelCritical
    val energyLevelFullyRecharged = vehicle.energyLevelFullyRecharged
    val energyLevelSufficientlyRecharged = vehicle.energyLevelSufficientlyRecharged
    val energyLevel = vehicle.energyLevel
    val integrationLevel = vehicle.integrationLevel
    val procState = vehicle.procState
    val transportOrder: String? = vehicle.transportOrder
    val orderSequence: String? = vehicle.orderSequence
    val currentPosition: String? = vehicle.currentPosition
    val currentDestination: String?
    val unfinishedSteps: List<String>
    val paused: Boolean = vehicle.paused
    val loadDevices: List<LoadHandlingDevice> = vehicle.loadHandlingDevices
    val errorInfos: List<VehicleProcessModel.ErrorInfo>? = getVehicleControllerOrNull(vehicle.name)?.errorInfos
    val owner: String = vehicle.owner   // AGV的当前占用者
    val isDominating = vehicle.isDominating // 是否被本机占用
    val allocations: List<String>
    val adapterEnabled = vehicle.adapterEnabled
    val relocStatus = vehicle.relocStatus
    
    init {
        val currentDriveOrder = getCurrentDriveOrderOfVehicle(vehicle)
        currentDestination = currentDriveOrder?.destination?.destination
        val steps = currentDriveOrder?.route?.steps ?: emptyList()
        unfinishedSteps =
                if (steps.size > vehicle.routeProgressIndex + 1) {
                    steps.subList(vehicle.routeProgressIndex + 1, steps.size).mapNotNull { step -> step.path }
                }
                else {
                    emptyList()
                }
        allocations = vehicle.allocations.toList()
    }
    
    private fun getCurrentDriveOrderOfVehicle(vehicle: Vehicle): DriveOrder? {
        val order = TransportOrderService.getOrderOrNull(vehicle.transportOrder) ?: return null
        if (order.processingVehicle != vehicle.name) return null
        return order.currentDriveOrder
    }
}