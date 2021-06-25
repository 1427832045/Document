package com.seer.srd.device.charger

import com.seer.srd.CONFIG
import com.seer.srd.Error404
import com.seer.srd.route.service.VehicleService
import com.seer.srd.vehicle.Vehicle
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object ChargerService {

    val managers: MutableMap<String, AbstractChargerManager> = ConcurrentHashMap()

    private val logger = LoggerFactory.getLogger(ChargerService::class.java)

    fun init() {
        if (CONFIG.chargers.isEmpty()) return
        for (chargerConfig in CONFIG.chargers) {
            val manager =
                if (chargerConfig.mode == "Tcp") ChargerManager(chargerConfig)
                else ChargerManagerAioTcp(chargerConfig)
            managers[chargerConfig.name] = manager
        }
    }

    @Synchronized
    fun dispose() {
        managers.values.forEach {
            try {
                it.dispose()
            } catch (e: Exception) {
                // logger.error("dispose charger ${it.config.name}", e)
                logger.error("dispose charger ${it.getChargerModel().config.name}", e)
            }
        }
        managers.clear()
    }

    /** 获取指定充电机 */
    fun getChargerByName(name: String): AbstractChargerManager {
        return managers[name] ?: throw Error404("不存在充电机【$name】！")
    }

    /** 获取所有充电机状态 */
    fun listChargerStatus(): List<ChargerModel> {
        return managers.values.map {
            it.getChargerModel()
        }
    }

    /** 获取指定充电机状态 */
    fun getChargerStatusByName(name: String): ChargerModel {
        return getChargerByName(name).getChargerModel()
    }

    fun cancelChargeByOrder(orderName: String) {
        if (managers.isNullOrEmpty()) return
        // 机器人在充电位置上才能撤销充电
        for (charger in listChargerStatus()) {
            val vehicle = charger.vehicleInfo
            if (vehicle.transportOrder == orderName) {
                val vehicleId = vehicle.id
                if (charger.turnedOn)
                    getChargerByName(charger.config.name).turnOn(false, "cause withdraw transportOrder")

                // 将机器人设置为【在线不接单状态】，防止再次创建自动充电任务
                if (vehicle.transportOrder.split("-").contains("Recharge")) {
                    logger.info("cancel charge by transportOrder=$orderName, and set vehicle=$vehicleId from ${vehicle.state} to TO_BE_RESPECTED.")
                    VehicleService.updateVehicleIntegrationLevel(vehicleId, Vehicle.IntegrationLevel.TO_BE_RESPECTED)
                }
                return
            }
        }

        logger.info("cancel charge failed when withdraw transportOrder cause no vehicle on any charger position executing order=$orderName.")
    }

    fun cancelChargeByVehicle(vehicleId: String) {
        if (managers.isNullOrEmpty()) return
        // 机器人在充电位置上才能撤销充电
        for (charger in listChargerStatus()) {
            val vehicle = charger.vehicleInfo
            val id = vehicle.id
            if (id == vehicleId) {
                if (charger.turnedOn)
                    getChargerByName(charger.config.name).turnOn(false, "by vehicle=$vehicleId")

                // 将机器人设置为【在线不接单状态】，防止再次创建自动充电任务
                logger.info("---- transportOrder=${vehicle.transportOrder}")
                if (vehicle.transportOrder.split("-").contains("Recharge")) {
                    logger.info("cancel charge by vehicle=$vehicle, and set it from ${vehicle.state} to TO_BE_RESPECTED.")
                    VehicleService.updateVehicleIntegrationLevel(vehicleId, Vehicle.IntegrationLevel.TO_BE_RESPECTED)
                }
                return
            }
        }

        logger.info("cancel charge by vehicle failed cause vehicle=$vehicleId not on charge position.")
    }
}

