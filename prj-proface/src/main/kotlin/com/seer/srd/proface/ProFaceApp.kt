package com.seer.srd.proface

import com.seer.srd.Application
import com.seer.srd.db.MongoDBManager
import com.seer.srd.eventbus.EventBus
import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.route.service.TransportOrderService
import com.seer.srd.route.service.VehicleService
import com.seer.srd.scheduler.backgroundCacheExecutor
import com.seer.srd.setVersion
import com.seer.srd.vehicle.Vehicle
import com.seer.srd.vehicle.VehicleManager
import com.seer.srd.vehicle.driver.AbstractVehicleCommAdapter
import com.seer.srd.vehicle.driver.VehicleDriverManager
import org.apache.commons.collections4.QueueUtils.emptyQueue
import org.litote.kmongo.and
import org.litote.kmongo.eq
import org.litote.kmongo.lt
import org.opentcs.drivers.vehicle.MovementCommand
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun main() {
    Application.initialize()
    ProFaceApp.init()
    EventBus.robotTaskFinishedEventBus.add(ProFaceApp::onRobotTaskFinished)
    Application.start()
    if (CUSTOM_CONFIG.finsTrigger) OkClientService.init()
}

object ProFaceApp{

    private val logger = LoggerFactory.getLogger(ProFaceApp::class.java)

    private val helper: ModbusTcpMasterHelper = ModbusTcpMasterHelper(CUSTOM_CONFIG.fireHost, CUSTOM_CONFIG.firePort)

    @Volatile
    private var checking = false

    private val executor = Executors.newScheduledThreadPool(1)

    val transferTaskMap: MutableMap<String, String> = ConcurrentHashMap()

    init {
        helper.connect()
        executor.scheduleAtFixedRate(this::checkSystem, 1000, 1000, TimeUnit.MILLISECONDS)
        backgroundCacheExecutor.submit {
            val unfinishedTransferTasks = MongoDBManager.collection<RobotTask>()
                .find(and(
                    RobotTask::state lt RobotTaskState.Success,
                    RobotTask::def eq "transfer")
                ).toList()
            val psTasks = unfinishedTransferTasks.filter { it.persistedVariables["psId"] != null }.distinctBy { it.persistedVariables["psId"] }
            psTasks.forEach { task ->
                val psId = task.persistedVariables["psId"]
                if (psId is String) transferTaskMap[psId] = task.id
                else logger.error("unknown psId=$psId, taskId=${task.id}")
            }
        }
    }

    fun init() {
        setVersion("proface", "1.0.4")
        registerRobotTaskComponents(ExtraComponent.extraComponent)
    }

    fun onRobotTaskFinished(task: RobotTask) {
        if (task.def.contains("transfer")) {
            val psId = task.persistedVariables["psId"] as String
            if (transferTaskMap.containsKey(psId) && transferTaskMap[psId] == task.id)
                transferTaskMap.remove(psId)
        }
    }

    private fun checkSystem() {
        synchronized(helper) {
            if (checking) return
            if (!CUSTOM_CONFIG.fire) return
            checking = true
            try {
                val status = helper.read02DiscreteInputs(CUSTOM_CONFIG.triggerAddr, 1, CUSTOM_CONFIG.fireUnitId, "fire trigger status")?.getByte(0)?.toInt()
                when (status) {
                  1 -> {
                      val vs = VehicleService.listVehicles()
                      if (vs.any { !it.paused }) {
                          logger.debug("fire status = 1, try to stop the world ...")

                          vs.forEach {
                              try {
                                  setVehiclePausedWhenNotPassingDoorOrLift(it.name, true)
                                  logger.debug("pause vehicle ${it.name} succeed")
                              } catch (e: Exception) {
                                  logger.debug("pause vehicle ${it.name} failed, ${e.message}")
                              }
                          }
                      }

                      logger.debug("The world stopped!")
                  }
                  0 -> {
                      val vs = VehicleService.listVehicles()
                      if (vs.any { it.paused }) {
                          logger.debug("fire status = 0, try to resume the world ...")
                          vs.forEach {
                              try {
                                  setVehiclePausedWhenNotPassingDoorOrLift(it.name, false)
                                  logger.debug("resume vehicle ${it.name} succeed")
                              } catch (e: Exception) {
                                  logger.debug("resume vehicle ${it.name} failed, ${e.message}")
                              }
                          }
                      }
                  }
                  else -> logger.error("未知的消防IO值: $status")
                }
            } catch (e: Exception) {
                logger.error("try to read fire status error", e)
            } finally {
              checking = false
            }

        }
    }
    private fun sentDoorOrLiftCommands(commands: Queue<MovementCommand>): Boolean {
        return commands.any { command ->
            command.properties.entries.any { entry ->
                entry.key.trim().contains("device:passDoor")
                    || entry.key.trim().contains("device:switchMap")
                    || entry.key.trim().contains("device:occupyLift")
                    || entry.key.trim().contains("device:unoccupyLift")
            }
        }
    }

    private fun setVehiclePausedWhenNotPassingDoorOrLift(vehicleName: String, pause: Boolean) {

        val adapter = VehicleDriverManager.getVehicleCommAdapterOrNull(vehicleName)

        if (adapter != null) {
            var ok: Boolean
            synchronized(adapter.lock()) {
                val sentQueue = adapter.getSentQueue()
                // 如果pause为false直接返回 ok = true
                // 如果pause为true则判断sentQueue中的命令
                ok = !pause || sentQueue.isEmpty() || (sentQueue.isNotEmpty() && !sentDoorOrLiftCommands(sentQueue))
            }
            if (ok) {
                VehicleManager.pauseVehicleByName(vehicleName, pause)
            }
        } else {
            logger.error("setVehiclePausedWhenNotPassingDoorOrLift ERROR, adapter=$adapter")
        }
    }
}