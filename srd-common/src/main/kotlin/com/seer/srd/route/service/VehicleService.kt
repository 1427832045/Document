package com.seer.srd.route.service

import com.seer.srd.BusinessError
import com.seer.srd.CONFIG
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.eventbus.EventBus.onVehicleChanged
import com.seer.srd.eventbus.EventBus.vehicleEventManager
import com.seer.srd.eventbus.VehicleChangedEvent
import com.seer.srd.eventlog.EventLogLevel
import com.seer.srd.eventlog.SystemEvent
import com.seer.srd.eventlog.recordSystemEventLog
import com.seer.srd.model.Triple
import com.seer.srd.route.globalSyncObject
import com.seer.srd.route.service.OrderSequenceService.getSequence
import com.seer.srd.route.service.TransportOrderService.getOrder
import com.seer.srd.scheduler.backgroundCacheExecutor
import com.seer.srd.vehicle.Vehicle
import com.seer.srd.vehicle.Vehicle.IntegrationLevel
import com.seer.srd.vehicle.Vehicle.ProcState
import com.seer.srd.vehicle.VehicleOutput
import com.seer.srd.vehicle.VehiclePersistable
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.litote.kmongo.set
import org.litote.kmongo.setTo
import org.opentcs.data.ObjectUnknownException
import org.opentcs.data.order.OrderConstants
import org.opentcs.drivers.vehicle.LoadHandlingDevice
import java.util.concurrent.ConcurrentHashMap

object VehicleService {
    
    private val vehiclesCache: MutableMap<String, Vehicle> = ConcurrentHashMap()
    
    fun getVehicleOrNull(name: String): Vehicle? {
        return vehiclesCache[name]
    }
    
    fun getVehicle(name: String): Vehicle {
        return vehiclesCache[name] ?: throw ObjectUnknownException("Unknown vehicle '$name'.")
    }
    
    fun listVehicles(): List<Vehicle> {
        return vehiclesCache.values.toList()
    }
    
    fun listVehiclesOutputs(): List<VehicleOutput> {
        return vehiclesCache.values.map { VehicleOutput(it) }
    }

    fun addNewVehicle(vehicle: Vehicle) {
        synchronized(globalSyncObject) {
            if (vehiclesCache.containsKey(vehicle.name)) throw BusinessError("Vehicle name existed ${vehicle.name}.")
            
            val c = collection<VehiclePersistable>()
            // 不覆盖数据库，数据库的优先级更高
            val vp = c.findOne(VehiclePersistable::id eq vehicle.name)
            if (vp == null) {
                c.insertOne(
                    VehiclePersistable(
                        vehicle.name,
                        vehicle.processableCategories,
                        vehicle.integrationLevel.name,
                        vehicle.currentPosition ?: ""
                    )
                )
                vehiclesCache[vehicle.name] = vehicle
            } else {
                if (CONFIG.startFromDB) {
                    vehiclesCache[vehicle.name] = vehicle.copy(
                        processableCategories = vp.categories,
                        integrationLevel = if (vp.integrationLevel != null) IntegrationLevel.valueOf(vp.integrationLevel)
                        else IntegrationLevel.TO_BE_UTILIZED,
                        procState = ProcState.IDLE
                    )
                } else {
                    vehiclesCache[vehicle.name] = vehicle
                }
            }
        }
    }
    
    private fun replaceVehicle(vehicle: Vehicle): Vehicle {
        synchronized(globalSyncObject) {
            checkVehicleError(vehicle)
            vehiclesCache[vehicle.name] = vehicle
        }
        onVehicleChanged(vehicle)
        return vehicle
    }

    private fun replaceVehicleWithoutEvent(vehicle: Vehicle): Vehicle {
        synchronized(globalSyncObject) {
            checkVehicleError(vehicle)
            vehiclesCache[vehicle.name] = vehicle
        }
        return vehicle
    }
    
    fun clear() {
        synchronized(globalSyncObject) {
            vehiclesCache.clear()
        }
    }
    
    // loadPlantModel之前，清空数据库
    fun clearDB() {
        synchronized(globalSyncObject) {
            collection<VehiclePersistable>().drop()
        }
    }
    
    fun updateVehicleIntegrationLevel(vehicleName: String, integrationLevel: IntegrationLevel) {
        synchronized(globalSyncObject) {
            val vehicle = getVehicle(vehicleName)
            require(
                !(vehicle.isProcessingOrder
                    && (integrationLevel == IntegrationLevel.TO_BE_IGNORED
                    || integrationLevel == IntegrationLevel.TO_BE_NOTICED))
            ) {
                String.format(
                    "%s: Cannot change integration level to %s while processing orders.",
                    vehicle.name, integrationLevel.name
                )
            }
            setVehicleIntegrationLevel(vehicleName, integrationLevel)
        }
        collection<VehiclePersistable>().updateOne(
            VehiclePersistable::id eq vehicleName,
            set(VehiclePersistable::integrationLevel setTo integrationLevel.name)
        )
    }
    
    fun updateVehicleProcessableCategories(vehicleName: String, processableCategories: Set<String>) {
        val categories: MutableSet<String> = HashSet(processableCategories)
        categories.add(OrderConstants.CATEGORY_NONE) // 故意为之
        synchronized(globalSyncObject) { setVehicleProcessableCategories(vehicleName, categories) }
        collection<VehiclePersistable>().updateOne(
            VehiclePersistable::id eq vehicleName,
            set(VehiclePersistable::categories setTo categories)
        )
    }

    fun updateVehicleProperties(vehicleName: String, properties: Map<String, String>) {
        synchronized(globalSyncObject) { setVehicleProperties(vehicleName, properties) }
    }

    fun updateVehicleAllocations(vehicleName: String, allocations: Set<String>) {
        synchronized(globalSyncObject) { setVehicleAllocations(vehicleName, allocations) }
    }

    fun updateVehicleEnergyLevel(vehicleName: String, energyLevel: Int) {
        synchronized(globalSyncObject) { setVehicleEnergyLevel(vehicleName, energyLevel) }
    }

    fun updateVehicleRelocStatus(vehicleName: String, relocStatus: Int) {
        synchronized(globalSyncObject) { setVehicleRelocStatus(vehicleName, relocStatus) }
    }
    
    fun updateVehicleLoadHandlingDevices(vehicleName: String, devices: List<LoadHandlingDevice>) {
        synchronized(globalSyncObject) { setVehicleLoadHandlingDevices(vehicleName, devices) }
    }
    
    fun updateVehicleNextPosition(vehicleName: String, pointName: String?) {
        synchronized(globalSyncObject) { setVehicleNextPosition(vehicleName, pointName) }
    }
    
    fun updateVehicleOrderSequence(vehicleName: String, sequenceName: String?) {
        synchronized(globalSyncObject) { setVehicleOrderSequence(vehicleName, sequenceName) }
    }
    
    fun updateVehicleOrientationAngle(vehicleName: String, angle: Double) {
        synchronized(globalSyncObject) { setVehicleOrientationAngle(vehicleName, angle) }
    }
    
    fun updateVehiclePosition(vehicleName: String, pointName: String?) {
        synchronized(globalSyncObject) {
            setVehiclePosition(vehicleName, pointName)
            // 刚启动的时候会把position置为null，如果不过滤的话就会把数据库里面的记录覆盖掉
            if (pointName != null) {
                collection<VehiclePersistable>().updateOne(
                    VehiclePersistable::id eq vehicleName,
                    set(VehiclePersistable::mockPosition setTo pointName)
                )
            }
        }
    }
    
    fun updateVehiclePrecisePosition(vehicleName: String, position: Triple?) {
        synchronized(globalSyncObject) { setVehiclePrecisePosition(vehicleName, position) }
    }
    
    fun updateVehicleProcState(vehicleName: String, state: ProcState) {
        synchronized(globalSyncObject) { setVehicleProcState(vehicleName, state) }
    }
    
    fun updateVehicleRouteProgressIndex(vehicleName: String, index: Int) {
        synchronized(globalSyncObject) { setVehicleRouteProgressIndex(vehicleName, index) }
    }
    
    fun updateVehicleState(vehicleName: String, state: Vehicle.State) {
        synchronized(globalSyncObject) { setVehicleState(vehicleName, state) }
    }
    
    fun updateVehicleTransportOrder(vehicleName: String, orderName: String?) {
        synchronized(globalSyncObject) { setVehicleTransportOrder(vehicleName, orderName) }
    }
    
    fun updateVehicleEnergyLevelGood(vehicleName: String, energyLevelGood: Int) {
        synchronized(globalSyncObject) { setVehicleEnergyLevelGood(vehicleName, energyLevelGood) }
    }
    
    fun updateVehicleEnergyLevelCritical(vehicleName: String, energyLevelCritical: Int) {
        synchronized(globalSyncObject) { setVehicleEnergyLevelCritical(vehicleName, energyLevelCritical) }
    }
    
    fun updateVehicleEnergyLevelFullyRecharged(vehicleName: String, energyLevelFullyRecharged: Int) {
        synchronized(globalSyncObject) {
            setVehicleEnergyLevelFullyRecharged(vehicleName, energyLevelFullyRecharged)
        }
    }
    
    fun updateVehicleEnergyLevelSufficientlyRecharged(vehicleName: String, energyLevelSufficientlyRecharged: Int) {
        synchronized(globalSyncObject) {
            setEnergyLevelSufficientlyRecharged(vehicleName, energyLevelSufficientlyRecharged)
        }
    }

    fun updateVehiclePaused(vehicleName: String, isPaused: Boolean) {
        synchronized(globalSyncObject) {
            setVehiclePaused(vehicleName, isPaused)
        }
    }

    fun updateVehicleAdapterEnabled(vehicleName: String, isEnabled: Boolean) {
        synchronized(globalSyncObject) {
            setVehicleAdapterEnabled(vehicleName, isEnabled)
        }
    }

    fun updateVehicleOwner(vehicleName: String, owner: String, isDominating: Boolean) {
        synchronized(globalSyncObject) {
            val oldVehicle = getVehicleOrNull(vehicleName) ?: throw ObjectUnknownException("Unknown vehicle '$vehicleName'.")
            val newVehicle = replaceVehicle(oldVehicle.copy(owner = owner, isDominating = isDominating))
            // no need to fire event
        }
    }

    private fun setVehicleEnergyLevel(vehicleName: String, energyLevel: Int) {
        val oldVehicle = getVehicleOrNull(vehicleName) ?: throw ObjectUnknownException("Unknown vehicle '$vehicleName'.")
        val newVehicle = replaceVehicle(oldVehicle.copy(energyLevel = energyLevel))
        fireVehicleChanged(newVehicle, oldVehicle)
    }

    private fun setVehicleRelocStatus(vehicleName: String, relocStatus: Int) {
        val oldVehicle = getVehicleOrNull(vehicleName) ?: throw ObjectUnknownException("Unknown vehicle '$vehicleName'.")
        val newVehicle = replaceVehicle(oldVehicle.copy(relocStatus = relocStatus))
        fireVehicleChanged(newVehicle, oldVehicle)
    }
    
    private fun setVehicleEnergyLevelCritical(vehicleName: String, energyLevel: Int) {
        val oldVehicle = getVehicleOrNull(vehicleName) ?: throw ObjectUnknownException("Unknown vehicle '$vehicleName'.")
        val newVehicle = replaceVehicle(oldVehicle.copy(energyLevelCritical = energyLevel))
        fireVehicleChanged(newVehicle, oldVehicle)
    }
    
    private fun setVehicleEnergyLevelGood(vehicleName: String, energyLevel: Int) {
        val oldVehicle = getVehicleOrNull(vehicleName) ?: throw ObjectUnknownException("Unknown vehicle '$vehicleName'.")
        val newVehicle = replaceVehicle(oldVehicle.copy(energyLevelGood = energyLevel))
        fireVehicleChanged(newVehicle, oldVehicle)
    }
    
    private fun setVehicleEnergyLevelFullyRecharged(vehicleName: String, energyLevel: Int) {
        val oldVehicle = getVehicleOrNull(vehicleName) ?: throw ObjectUnknownException("Unknown vehicle '$vehicleName'.")
        val newVehicle = replaceVehicle(oldVehicle.copy(energyLevelFullyRecharged = energyLevel))
        fireVehicleChanged(newVehicle, oldVehicle)
    }
    
    private fun setEnergyLevelSufficientlyRecharged(vehicleName: String, energyLevel: Int) {
        val oldVehicle = getVehicleOrNull(vehicleName) ?: throw ObjectUnknownException("Unknown vehicle '$vehicleName'.")
        val newVehicle = replaceVehicle(oldVehicle.copy(energyLevelSufficientlyRecharged = energyLevel))
        fireVehicleChanged(newVehicle, oldVehicle)
    }
    
    private fun setVehicleLoadHandlingDevices(vehicleName: String, devices: List<LoadHandlingDevice>) {
        val oldVehicle = getVehicleOrNull(vehicleName) ?: throw ObjectUnknownException("Unknown vehicle '$vehicleName'.")
        val newVehicle = replaceVehicle(oldVehicle.copy(loadHandlingDevices = devices))
        fireVehicleChanged(newVehicle, oldVehicle)
    }
    
    fun setVehicleState(vehicleName: String, newState: Vehicle.State) {
        val oldVehicle = getVehicle(vehicleName)
        val newVehicle = replaceVehicle(oldVehicle.copy(state = newState))
        fireVehicleChanged(newVehicle, oldVehicle)
    }
    
    private fun setVehicleIntegrationLevel(vehicleName: String, integrationLevel: IntegrationLevel) {
        val oldVehicle = getVehicle(vehicleName)
        val newVehicle = replaceVehicle(oldVehicle.copy(integrationLevel = integrationLevel))
        fireVehicleChanged(newVehicle, oldVehicle)
    }
    
    fun setVehicleProcState(vehicleName: String, newState: ProcState) {
        val oldVehicle = getVehicle(vehicleName)
        val newVehicle = replaceVehicle(oldVehicle.copy(procState = newState))
        fireVehicleChanged(newVehicle, oldVehicle)
    }
    
    private fun setVehicleProcessableCategories(vehicleName: String, processableCategories: Set<String>) {
        val oldVehicle = getVehicle(vehicleName)
        val newVehicle = replaceVehicle(oldVehicle.copy(processableCategories = processableCategories))
        fireVehicleChanged(newVehicle, oldVehicle)
    }

    private fun setVehicleProperties(vehicleName: String, properties: Map<String, String>) {
        val oldVehicle = getVehicle(vehicleName)
        val newVehicle = replaceVehicle(oldVehicle.copy(properties = oldVehicle.properties.plus(properties)))
        fireVehicleChanged(newVehicle, oldVehicle)
    }

    private fun setVehicleAllocations(vehicleName: String, allocations: Set<String>) {
        val oldVehicle = getVehicle(vehicleName)
        val newVehicle = replaceVehicleWithoutEvent(oldVehicle.copy(allocations = allocations))
        // no need to fire event
    }
    
    private fun setVehiclePosition(vehicleName: String, newPosPointName: String?) {
        val oldVehicle = getVehicle(vehicleName)
        // If the vehicle was occupying a point before, clear it and send an event.
        val points = PlantModelService.getPlantModel().points
        val oldCurrentPosition = oldVehicle.currentPosition
        if (oldCurrentPosition != null) {
            val oldVehiclePos = points[oldCurrentPosition]!!
            points[oldCurrentPosition] = oldVehiclePos.copy(occupyingVehicle = null)
        }
        // If the vehicle is occupying a point now, set that and send an event.
        if (newPosPointName != null) {
            val newVehiclePos = points[newPosPointName]!!
            points[newVehiclePos.name] = newVehiclePos.copy(occupyingVehicle = vehicleName)
        }
        val newVehicle = replaceVehicle(oldVehicle.copy(currentPosition = newPosPointName))
        fireVehicleChanged(newVehicle, oldVehicle)
    }
    
    private fun setVehicleNextPosition(vehicleName: String, newPositionPointName: String?) {
        val oldVehicle = getVehicle(vehicleName)
        val newVehicle = replaceVehicle(oldVehicle.copy(nextPosition = newPositionPointName))
        fireVehicleChanged(newVehicle, oldVehicle)
    }
    
    private fun setVehiclePrecisePosition(vehicleName: String, newPosition: Triple?) {
        val oldVehicle = getVehicle(vehicleName)
        val newVehicle = replaceVehicle(oldVehicle.copy(precisePosition = newPosition))
        fireVehicleChanged(newVehicle, oldVehicle)
    }
    
    private fun setVehicleOrientationAngle(vehicleName: String, angle: Double) {
        val oldVehicle = getVehicle(vehicleName)
        val newVehicle = replaceVehicle(oldVehicle.copy(orientationAngle = angle))
        fireVehicleChanged(newVehicle, oldVehicle)
    }
    
    fun setVehicleTransportOrder(vehicleName: String, orderName: String?) {
        val oldVehicle = getVehicle(vehicleName)
        val newVehicle = if (orderName == null) {
            replaceVehicle(oldVehicle.copy(transportOrder = null, lastTerminateTimeMs = System.currentTimeMillis()))
        } else {
            val order = getOrder(orderName)
            replaceVehicle(oldVehicle.copy(transportOrder = order.name))
        }
        fireVehicleChanged(newVehicle, oldVehicle)
    }
    
    fun setVehicleOrderSequence(vehicleName: String, seqName: String?) {
        val oldVehicle = getVehicle(vehicleName)
        val newVehicle = if (seqName == null) {
            replaceVehicle(oldVehicle.copy(orderSequence = null))
        } else {
            val seq = getSequence(seqName)
            replaceVehicle(oldVehicle.copy(orderSequence = seq.name))
        }
        fireVehicleChanged(newVehicle, oldVehicle)
    }
    
    private fun setVehicleRouteProgressIndex(vehicleName: String, index: Int) {
        val oldVehicle = getVehicle(vehicleName)
        val newVehicle = replaceVehicle(oldVehicle.copy(routeProgressIndex = index))
        fireVehicleChanged(newVehicle, oldVehicle)
    }
    
    private fun fireVehicleChanged(newVehicle: Vehicle, oldVehicle: Vehicle) {
        vehicleEventManager.fire(VehicleChangedEvent(oldVehicle, newVehicle))
    }
    
    private fun checkVehicleError(newVehicle: Vehicle) {
        val oldVehicle = vehiclesCache[newVehicle.name]
        // 之前有错，现在没错
        if ((oldVehicle != null && hasError(oldVehicle.state)) && !hasError(newVehicle.state)) {
            backgroundCacheExecutor.submit {
                recordSystemEventLog("Vehicle", EventLogLevel.Info, SystemEvent.VehicleRestore, newVehicle.name)
            }
        }
        // 之前没错，现在有错
        if (hasError(newVehicle.state) && (oldVehicle == null || !hasError(oldVehicle.state))) {
            backgroundCacheExecutor.submit {
                recordSystemEventLog("Vehicle", EventLogLevel.Error, SystemEvent.VehicleError, newVehicle.name)
            }
        }
    }
    
    private fun hasError(vehicleState: Vehicle.State): Boolean {
        return vehicleState == Vehicle.State.ERROR || vehicleState == Vehicle.State.UNAVAILABLE
    }

    private fun setVehiclePaused(vehicleName: String, isPaused: Boolean) {
        val oldVehicle = getVehicleOrNull(vehicleName) ?: throw ObjectUnknownException("Unknown vehicle '$vehicleName'.")
        val newVehicle = replaceVehicle(oldVehicle.copy(paused = isPaused))
        fireVehicleChanged(newVehicle, oldVehicle)
    }

    private fun setVehicleAdapterEnabled(vehicleName: String, isEnabled: Boolean) {
        val oldVehicle = getVehicleOrNull(vehicleName) ?: throw ObjectUnknownException("Unknown vehicle '$vehicleName'.")
        val newVehicle = replaceVehicle(oldVehicle.copy(adapterEnabled = isEnabled))
        fireVehicleChanged(newVehicle, oldVehicle)
    }
    
}