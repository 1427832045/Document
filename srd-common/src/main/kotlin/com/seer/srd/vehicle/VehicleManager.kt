package com.seer.srd.vehicle

import com.seer.srd.SystemError
import com.seer.srd.route.kernelExecutor
import com.seer.srd.route.service.VehicleService
import com.seer.srd.vehicle.Vehicle.IntegrationLevel
import com.seer.srd.vehicle.driver.VehicleDriverManager.getVehicleCommAdapterOrNull
import org.opentcs.components.kernel.services.DispatcherService
import org.opentcs.data.ObjectUnknownException
import org.opentcs.kernel.getInjector

object VehicleManager {
    
    // 暂停不是修改 Vehicle 中车的状态，而是直接发给底层，所以不在 Service 中
    fun pauseAllVehicles(value: Boolean) {
        val vehicles = VehicleService.listVehicles()
        kernelExecutor.submit {
            vehicles.forEach { vehicle ->
                val adapter = getVehicleCommAdapterOrNull(vehicle.name) ?: return@forEach
                VehicleService.updateVehiclePaused(vehicle.name, value)
                adapter.setVehiclePaused(value)
            }
        }
    }

    fun enableAdapter(name: String) {
        val adapter = getVehicleCommAdapterOrNull(name) ?: return
        adapter.enable()
    }

    fun disableAdapter(name: String) {
        val adapter = getVehicleCommAdapterOrNull(name) ?: return
        adapter.disable()
    }
    
    fun acquireVehicle(name: String): Boolean {
        val adapter = getVehicleCommAdapterOrNull(name) ?: return false
        return adapter.acquireVehicle()
    }
    
    fun disownVehicle(name: String): Boolean {
        val adapter = getVehicleCommAdapterOrNull(name) ?: return false
        return adapter.disownVehicle()
    }
    
    fun clearVehicleErrors(name: String): Boolean {
        val adapter = getVehicleCommAdapterOrNull(name) ?: return false
        return adapter.clearAllErrors()
    }
    
    fun pauseVehicleByName(name: String, value: Boolean) {
        val vehicle: Vehicle = VehicleService.getVehicleOrNull(name)
            ?: throw ObjectUnknownException("Unknown vehicle: $name")
        kernelExecutor.submit {
            val adapter = getVehicleCommAdapterOrNull(vehicle.name) ?: return@submit
            VehicleService.updateVehiclePaused(vehicle.name, value)
            adapter.setVehiclePaused(value)
        }
    }
    
    fun withdrawByVehicle(name: String, immediate: Boolean, disableVehicle: Boolean) {
        val injector = getInjector() ?: throw SystemError("No Injector")
        val dispatcherService = injector.getInstance(DispatcherService::class.java)
        
        val vehicle = VehicleService.getVehicleOrNull(name) ?: throw ObjectUnknownException("Unknown vehicle: $name")
        kernelExecutor.submit {
            if (disableVehicle) {
                VehicleService.updateVehicleIntegrationLevel(vehicle.name, IntegrationLevel.TO_BE_RESPECTED)
            }
            dispatcherService.withdrawByVehicle(vehicle.name, immediate, disableVehicle)
        }
    }
    
    fun setAllVehiclesIntegrationLevel(value: String) {
        val vehicles = VehicleService.listVehicles()
        val level = IntegrationLevel.valueOf(value)
        kernelExecutor.submit {
            vehicles.forEach { vehicle -> VehicleService.updateVehicleIntegrationLevel(vehicle.name, level) }
        }
    }
    
    fun setVehicleProcessableCategories(name: String, values: List<String>) {
        val vehicle = VehicleService.getVehicleOrNull(name) ?: throw ObjectUnknownException("Unknown vehicle: $name")
        kernelExecutor.submit {
            VehicleService.updateVehicleProcessableCategories(vehicle.name, values.toSet())
        }
    }

    fun setVehicleProperties(name: String, values: Map<String, String>) {
        val vehicle = VehicleService.getVehicleOrNull(name) ?: throw ObjectUnknownException("Unknown vehicle: $name")
        kernelExecutor.submit {
            VehicleService.updateVehicleProperties(vehicle.name, values)
        }
    }
    
    fun setVehicleIntegrationLevel(name: String, value: String) {
        val vehicle = VehicleService.getVehicleOrNull(name) ?: throw ObjectUnknownException("Unknown vehicle: $name")
        
        val level = IntegrationLevel.valueOf(value)
        kernelExecutor.submit { VehicleService.updateVehicleIntegrationLevel(vehicle.name, level) }
    }
    
    fun setVehicleEnergyLevelGood(name: String, level: Int) {
        val vehicle = VehicleService.getVehicleOrNull(name) ?: throw ObjectUnknownException("Unknown vehicle: $name")
        kernelExecutor.submit { VehicleService.updateVehicleEnergyLevelGood(vehicle.name, level) }
    }
    
    fun setVehicleEnergyLevelCritical(name: String, level: Int) {
        val vehicle = VehicleService.getVehicleOrNull(name) ?: throw ObjectUnknownException("Unknown vehicle: $name")
        kernelExecutor.submit { VehicleService.updateVehicleEnergyLevelCritical(vehicle.name, level) }
    }
    
    fun setVehicleEnergyLevelFullyRecharged(name: String, level: Int) {
        val vehicle = VehicleService.getVehicleOrNull(name) ?: throw ObjectUnknownException("Unknown vehicle: $name")
        kernelExecutor.submit { VehicleService.updateVehicleEnergyLevelFullyRecharged(vehicle.name, level) }
    }
    
    fun setVehicleEnergyLevelSufficientlyRecharged(name: String, level: Int) {
        val vehicle = VehicleService.getVehicleOrNull(name) ?: throw ObjectUnknownException("Unknown vehicle: $name")
        kernelExecutor.submit { VehicleService.updateVehicleEnergyLevelSufficientlyRecharged(vehicle.name, level) }
    }
    
}




