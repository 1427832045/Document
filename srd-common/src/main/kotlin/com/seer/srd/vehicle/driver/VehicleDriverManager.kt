package com.seer.srd.vehicle.driver

import com.seer.srd.BusinessError
import com.seer.srd.SystemError
import com.seer.srd.route.VehicleCommAdapterIOType
import com.seer.srd.route.routeConfig
import com.seer.srd.route.service.VehicleService
import com.seer.srd.vehicle.driver.io.tcp.VehicleAdapterTcpServer
import org.opentcs.components.kernel.Scheduler
import org.opentcs.components.kernel.services.DispatcherService
import com.seer.srd.vehicle.Vehicle
import com.seer.srd.vehicle.driver.io.tcp.VehicleAdapterAioTcpServer
import org.opentcs.drivers.vehicle.VehicleController
import org.opentcs.kernel.getInjector
import org.opentcs.kernel.vehicles.DefaultVehicleController
import org.opentcs.strategies.basic.dispatching.RerouteUtil
import org.slf4j.LoggerFactory

object VehicleDriverManager {
    
    private val logger = LoggerFactory.getLogger("com.seer.srd.vehicle.driver")
    
    private var initialized = false
    
    private val vehicleCommAdapterMap: MutableMap<String, AbstractVehicleCommAdapter> = HashMap()
    
    private val vehicleControllerMap: MutableMap<String, VehicleController> = HashMap()
    
    @Synchronized
    fun init() {
        if (initialized) {
            logger.warn("VehicleDriverManager already initialized.")
            return
        }
        logger.info("Init VehicleDriverManager")
        
        val vehicles = VehicleService.listVehicles()
        
        if(!routeConfig.newCommAdapter) {
            if (routeConfig.commAdapterIO == VehicleCommAdapterIOType.Tcp) VehicleAdapterTcpServer.init()
            if (routeConfig.commAdapterIO == VehicleCommAdapterIOType.AioTcp) VehicleAdapterAioTcpServer.init()
        }
        logger.info("Init vehicle communication adapter")
        for (vehicle in vehicles) initVehicleCommAdapterAndController(vehicle)
        
        initialized = true
    }
    
    @Synchronized
    fun dispose() {
        if (!initialized) {
            logger.debug("VehicleDriverManager not initialized.")
            return
        }
        logger.info("Dispose VehicleDriverManager")
        
        if(!routeConfig.newCommAdapter) {
            if (routeConfig.commAdapterIO == VehicleCommAdapterIOType.Tcp) VehicleAdapterTcpServer.dispose()
            if (routeConfig.commAdapterIO == VehicleCommAdapterIOType.AioTcp) VehicleAdapterAioTcpServer.dispose()
        }
        // to list to copy to prevent ConcurrentModificationException
        vehicleCommAdapterMap.keys.toList().forEach(this::disposeVehicleCommAdapterAndController)
        vehicleCommAdapterMap.clear()
        vehicleControllerMap.clear()
        
        initialized = false
    }
    
    
    @Synchronized
    fun getVehicleCommAdapter(vehicleName: String): AbstractVehicleCommAdapter {
        return vehicleCommAdapterMap[vehicleName] ?: throw BusinessError("No adapter for vehicle $vehicleName")
    }
    
    @Synchronized
    fun getVehicleCommAdapterOrNull(vehicleName: String): AbstractVehicleCommAdapter? {
        return vehicleCommAdapterMap[vehicleName]
    }
    
    @Synchronized
    fun getVehicleController(vehicleName: String): VehicleController {
        return vehicleControllerMap[vehicleName] ?: throw BusinessError("No controller for vehicle $vehicleName")
    }
    
    @Synchronized
    fun getVehicleControllerOrNull(vehicleName: String): VehicleController? {
        return vehicleControllerMap[vehicleName]
    }
    
    @Synchronized
    private fun initVehicleCommAdapterAndController(vehicle: Vehicle) {
        disposeVehicleCommAdapterAndController(vehicle.name)
        
        val injector = getInjector() ?: throw SystemError("No Injector")
        
        val dispatcherService = injector.getInstance(DispatcherService::class.java)
        val scheduler = injector.getInstance(Scheduler::class.java)
        val rerouteUtil = injector.getInstance(RerouteUtil::class.java)
        
        // adapter ----->
        val adapter = if (routeConfig.newCommAdapter) {
            logger.info("Using new comm adapter")
            val ip = vehicle.properties["robot:ip"] ?: "99.99.99.99"
            if (ip == "99.99.99.99")
                logger.error("Vehicle ${vehicle.name}'s IP is not configured.")
            NewVehicleCommAdapter(vehicle, ip)
        } else {
            logger.info("Using old comm adapter")
            VehicleCommAdapter(vehicle)
        }
        
        adapter.initialize()
        adapter.enable() // 直接启用！！
        
        vehicleCommAdapterMap[vehicle.name] = adapter
        
        // controller ----->
        
        val controller = DefaultVehicleController(vehicle, adapter, dispatcherService, scheduler, rerouteUtil)
        
        controller.initialize()
        
        vehicleControllerMap[vehicle.name] = controller
    }
    
    @Synchronized
    private fun disposeVehicleCommAdapterAndController(vehicleName: String) {
        val adapter = vehicleCommAdapterMap.remove(vehicleName)
        adapter?.disable()
        adapter?.terminate()
        
        val controller = vehicleControllerMap.remove(vehicleName)
        controller?.terminate()
        
        // 参考 DefaultVehicleControllerPool
        val vehicle = VehicleService.getVehicle(vehicleName)
        VehicleService.updateVehiclePosition(vehicle.name, null)
    }
    
}