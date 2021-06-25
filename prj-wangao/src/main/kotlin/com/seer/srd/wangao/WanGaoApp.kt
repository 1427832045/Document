package com.seer.srd.wangao

import com.mongodb.client.model.UpdateOptions
import com.seer.srd.Application
import com.seer.srd.db.MongoDBManager
import com.seer.srd.eventbus.EventBus.robotTaskFinishedEventBus
import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.route.service.VehicleService
import com.seer.srd.setVersion
import com.seer.srd.util.loadConfig
import com.seer.srd.vehicle.Vehicle
import com.seer.srd.wangao.ExtraComponents.extraComponents
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.exists
import org.litote.kmongo.findOne
import org.litote.kmongo.set
import org.litote.kmongo.setTo
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.time.Instant
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

fun main() {
    setVersion("l2", "3.0.17")

    Application.initialize()

    registerRobotTaskComponents(extraComponents)
    robotTaskFinishedEventBus.add(WanGaoApp::onRobotTaskFinished)

    Application.start()
}

object WanGaoApp {

    @Volatile
    var checking = false

    @Volatile
    var lastAgvSignal: Byte? = null

    @Volatile
    var errorTime: Instant? = null

    private val logger = LoggerFactory.getLogger(this::class.java)

    val modBusHelper = ModbusTcpMasterHelper(CUSTOM_CONFIG.deviceHost, CUSTOM_CONFIG.devicePort)

    private val executor = Executors.newScheduledThreadPool(1)

    init {
        modBusHelper.connect()

        // 重启后设置errorTime为最后一次记录的时间
        val info = MongoDBManager.collection<AgvInfo>().findOne()
        if (info != null) {
            errorTime = info.time
            lastAgvSignal = info.lastSignal
        }

        executor.scheduleAtFixedRate(::onVehicleStatus, 1, 1, TimeUnit.SECONDS)
    }


    fun onRobotTaskFinished(task: RobotTask) {
        try {
            if (task.def.contains("down"))
                modBusHelper.write10MultipleRegisters(CUSTOM_CONFIG.agvAddr, 1, byteArrayOf(0, 0), 1, "agv动作复位")

        } catch (e: Exception) {
            logger.error("onRobotTaskFinished error", e)
        } finally {
            // 任务结束后，lastAgvSignal复位
            lastAgvSignal = 0
            // 持久化错误记录
            MongoDBManager.collection<AgvInfo>().updateOne(
                AgvInfo::id exists true, set(AgvInfo::lastSignal setTo 0),
                UpdateOptions().upsert(true))
            logger.debug("onRobotTaskFinished reset lastAgvSignal = 0")


        }

    }

    /**
     *
     * 每秒监听AGV状态:
     * 1. 当AGV状态为Error时，把AGV动作地址置为0，表示错误的地址置为1
     * 2. 当AGV状态不为Error时，把AGV动作地址复位成上一个状态(上一个状态的值在)，表示错误的地址复位，置为0
     */
    private fun onVehicleStatus() {
        synchronized(modBusHelper) {
            if (checking) return
            checking = true
            try {
                val vehicles = VehicleService.listVehicles()
                vehicles.forEach { vehicle ->
                    // 错误状态，复位agv的动作相关modbus为0，并把表示错误的地址置为1
                    if (Objects.equals(vehicle.state, Vehicle.State.ERROR)) {
                        modBusHelper.write10MultipleRegisters(CUSTOM_CONFIG.agvAddr, 1, byteArrayOf(0, 0), 1, "agv动作复位")
                        modBusHelper.write10MultipleRegisters(CUSTOM_CONFIG.agvErrorAddr, 1, byteArrayOf(0, 1), 1, "agv异常")

                        // 持久化错误记录
                        errorTime = Instant.now()
                        MongoDBManager.collection<AgvInfo>().updateOne(
                            AgvInfo::id exists true, set(AgvInfo::time setTo errorTime),
                            UpdateOptions().upsert(true))
                        logger.warn("agv state error on $errorTime")
                    } else {
                        // 当从错误恢复时，复位agvErrorAddr, 恢复agvAddr的值
                        if (errorTime != null) {
                            modBusHelper.write10MultipleRegisters(CUSTOM_CONFIG.agvErrorAddr, 1, byteArrayOf(0, 0), 1, "agv异常复位")
                            if (lastAgvSignal != null)
                                modBusHelper.write10MultipleRegisters(CUSTOM_CONFIG.agvAddr, 1, byteArrayOf(0, lastAgvSignal as Byte), 1, "agv动作恢复")
                            else modBusHelper.write10MultipleRegisters(CUSTOM_CONFIG.agvAddr, 1, byteArrayOf(0, 0), 1, "agv动作恢复")

                            // 持久化错误复位记录
                            errorTime = null
                            MongoDBManager.collection<AgvInfo>().updateOne(
                                AgvInfo::id exists true, set(AgvInfo::time setTo null),
                                UpdateOptions().upsert(true))
                            logger.warn("agv state normal on ${Instant.now()}")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("onVehicleStatus error", e)
            } finally {
                checking = false
            }
        }
    }

    fun disconnect() {
        modBusHelper.disconnect()
    }
}

class CustomConfig {
    var deviceHost: String = "127.0.0.1"
    var devicePort: Int = 502
    var deviceAddr = 0
    var deviceErrorAddr = 1
    var agvAddr = 10
    var agvErrorAddr = 11
}

data class AgvInfo(
    @BsonId
    val id: ObjectId = ObjectId(),
    val time: Instant? = null,
    val lastSignal: Byte? = null
)