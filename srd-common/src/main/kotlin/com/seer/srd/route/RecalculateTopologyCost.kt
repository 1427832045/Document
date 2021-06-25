package com.seer.srd.route

import com.seer.srd.SystemError
import com.seer.srd.route.service.PlantModelService
import com.seer.srd.route.service.VehicleService
import com.seer.srd.vehicle.Vehicle
import com.seer.srd.vehicle.driver.VehicleDriverManager
import org.opentcs.components.kernel.services.RouterService
import org.opentcs.kernel.getInjector
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("com.seer.srd.route")

private const val KEY = "robot:routingCostForward"

fun recalculateTopologyCost() {
    if (!routeConfig.useDynamicTopologyCost) return
    LOG.info("Recalculate topology cost.")
    var needRecalculate = false
    val vehicleExecutingTooLongMs = if (routeConfig.vehicleSimulation == VehicleSimulation.None) routeConfig.vehicleExecutingTooLongMs
        else (routeConfig.vehicleExecutingTooLongMs / routeConfig.simulationTimeFactor).toLong()
    try {
        LOG.info("Clearing all dynamic costs.")
        // 找到带有 KEY 的路径
        val pathsWithValue = PlantModelService.getPlantModel().paths.filter { it.value.properties.containsKey(KEY) }
        if (pathsWithValue.isNotEmpty()) needRecalculate = true
        // 先清掉所有的 KEY
        pathsWithValue.forEach { PlantModelService.setDynamicPathProperty(it.key, KEY, null) }
        // 寻找在线的车
        val vehiclesOnLine = VehicleService.listVehicles().filter {
            (it.integrationLevel == Vehicle.IntegrationLevel.TO_BE_UTILIZED || it.integrationLevel == Vehicle.IntegrationLevel.TO_BE_RESPECTED)
        }
        // 对有故障的车，让其周围的路径权值增大
        vehiclesOnLine.filter {
            (it.state == Vehicle.State.ERROR || it.state == Vehicle.State.UNAVAILABLE) && it.currentPosition != null
        }.forEach { it ->
            needRecalculate = true
            LOG.info("Vehicle ${it.name} on ${it.currentPosition} is ${it.state}, increase cost to ${it.currentPosition}.")
            val point = PlantModelService.getPointIfNameIs(it.currentPosition!!)
            point?.incomingPaths?.forEach {
                    pathName -> PlantModelService.setDynamicPathProperty(pathName, KEY, routeConfig.errorVehicleCostFactor.toString())
            }
        }
        // 对长时间运行状态的车，让其周围的路径权值增大
        vehiclesOnLine.filter {
            (it.state == Vehicle.State.IDLE || it.state == Vehicle.State.EXECUTING) && it.currentPosition != null
        }.forEach {
            val controller = VehicleDriverManager.getVehicleController(it.name)
            val adapter = VehicleDriverManager.getVehicleCommAdapter(it.name)
            val executeTimeMs: Long
//            val hasSentCommand: Boolean
            synchronized(adapter.lock()) {
//                hasSentCommand = controller.commandsSent.isNotEmpty()
                executeTimeMs = System.currentTimeMillis() - (controller.latestSendOrFinishCommandTime ?: System.currentTimeMillis()) //routeConfig.vehicleExecutingTooLongMs
            }
            // 如果/*存在已经下发的命令，但*/长时间没有下发新命令，或者完成过老命令了，说明车子可能遇到问题
            if (executeTimeMs > vehicleExecutingTooLongMs/* && hasSentCommand*/)
            {
                needRecalculate = true
                LOG.info("Vehicle ${it.name} on ${it.currentPosition} is ${it.state} for $executeTimeMs ms, increase cost to ${it.currentPosition}.")
                val point = PlantModelService.getPointIfNameIs(it.currentPosition!!)
                point?.incomingPaths?.forEach {
                        pathName -> PlantModelService.setDynamicPathProperty(pathName, KEY,
                    (executeTimeMs / 1000.0 * routeConfig.vehicleCostFactorPs).toLong().toString())   // 毫秒 -> 秒，秒 * vehicleCostFactorPs
                }
            }
        }

        // 触发重新规划
        if (needRecalculate) {
            val injector = getInjector() ?: throw SystemError("No Injector")
            val routerService = injector.getInstance(RouterService::class.java)
            routerService.updateRoutingTopology()
            LOG.info("Update topology cost finished.")
        } else {
            LOG.info("Exit recalculating topology cost.")
        }
    } catch (e: Exception) {
        LOG.error("Recalculate topology cost, ", e)
    }
}