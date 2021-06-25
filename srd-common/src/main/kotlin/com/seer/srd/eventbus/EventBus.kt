package com.seer.srd.eventbus

import com.seer.srd.device.lift.LiftModel
import com.seer.srd.device.charger.ChargerModel
import com.seer.srd.device.pager.PagerService
import com.seer.srd.domain.ChangeTrace
import com.seer.srd.eventlog.ErrorInfoLogger.statOnVehicleErrorInfo
import com.seer.srd.eventlog.StoreSiteChangeLogger
import com.seer.srd.eventlog.VehicleStateLogger.statOnVehicleChanged
import com.seer.srd.http.WebSocketManager.broadcastByWebSocket
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.getRobotTaskDef
import com.seer.srd.route.service.TransportOrderOutput2
import com.seer.srd.route.service.VehicleService.listVehiclesOutputs
import com.seer.srd.scheduler.backgroundFixedExecutor
import com.seer.srd.storesite.StoreSite
import com.seer.srd.util.ListenerManager
import com.seer.srd.vehicle.Vehicle
import org.opentcs.data.order.TransportOrder
import java.time.Instant
import java.util.concurrent.Executors

object EventBus {

    val vehicleEventManager = ListenerManager<VehicleChangedEvent>()

    val robotTaskCreatedEventBus = ListenerManager<RobotTask>()
    val robotTaskUpdatedEventBus = ListenerManager<RobotTask>()
    val robotTaskFinishedEventBus = ListenerManager<RobotTask>()

    val storeSitesEventBus = ListenerManager<List<ChangeTrace<StoreSite>>>()

    private val vehicleChangedExecutor = Executors.newSingleThreadExecutor()
    private val vehicleDetailsChangedExecutor = Executors.newSingleThreadExecutor()
    private val robotTaskChangedExecutor = Executors.newSingleThreadExecutor()
    private val storeSiteChangedExecutor = Executors.newSingleThreadExecutor()
    private val chargerChangedExecutors = Executors.newSingleThreadExecutor()
    private val liftChangedExecutors = Executors.newSingleThreadExecutor()
    private val transportOrderChangedExecutors = Executors.newSingleThreadExecutor()
    private val agvErrorInfoExecutors = Executors.newSingleThreadExecutor()

    fun onVehicleChanged(vehicle: Vehicle) {
        vehicleChangedExecutor.submit { broadcastByWebSocket("vehicle-changed", listVehiclesOutputs()) } // 若不异步会死锁
        backgroundFixedExecutor.submit { statOnVehicleChanged(vehicle) }
    }

    fun checkVehicleErrorInfo() {
        agvErrorInfoExecutors.submit { statOnVehicleErrorInfo() }
    }

    fun onVehiclesDetailsChanged(vehicles: List<Map<*, *>>) {
        vehicleDetailsChangedExecutor.submit { broadcastByWebSocket("vehicles-details-changed", vehicles) }
        //vehicleDetailsEventBus.fire(vehicles)
    }

    fun onRobotTaskCreated(task: RobotTask) {
        val taskDef = getRobotTaskDef(task.def)
        val msg = mapOf(
            "id" to task.id,
            "def" to task.def,
            "description" to (taskDef?.description ?: ""),
            "outOrderNo" to task.outOrderNo,
            "workTypes" to task.workTypes, // TODO
            "workStations" to task.workStations// TODO
        )
        robotTaskChangedExecutor.submit {
            broadcastByWebSocket("Task::Created", msg)
            robotTaskCreatedEventBus.fire(task)
        }
    }

    fun onRobotTaskUpdated(task: RobotTask) {
        val msg = mapOf(
            "id" to task.id,
            "def" to task.def,
            "workTypes" to task.workTypes, // TODO
            "workStations" to task.workStations // TODO
        )
        robotTaskChangedExecutor.submit {
            broadcastByWebSocket("Task::Updated", msg)
            robotTaskUpdatedEventBus.fire(task)
        }
    }

    fun onRobotTaskFinished(task: RobotTask) {
        val taskDef = getRobotTaskDef(task.def)
        val msg = mapOf(
            "id" to task.id,
            "def" to task.def,
            "description" to (taskDef?.description ?: ""),
            "outOrderNo" to task.outOrderNo,
            "workTypes" to task.workTypes, //TODO
            "workStations" to task.workStations,//TODO
            "createdOn" to task.createdOn,
            "finishedOn" to task.finishedOn,
            "state" to task.state,
            "persistedVariables" to task.persistedVariables
        )
        robotTaskChangedExecutor.submit {
            broadcastByWebSocket("Task::Finished", msg)
            robotTaskFinishedEventBus.fire(task)
        }
        backgroundFixedExecutor.submit {
            PagerService.getPagerByTaskId(task.id).updateSignalValueByTask(task)
        }
    }

    fun onRobotTaskRemoved() {
        robotTaskChangedExecutor.submit {
            broadcastByWebSocket("Task::Removed")
        }
    }

    fun onStoreSitesChanged(changes: List<ChangeTrace<StoreSite>>, remark: String) {
        val now = Instant.now()
        backgroundFixedExecutor.submit {
            broadcastByWebSocket("StoreSites::Changes", changes.map { it.to }) // 只传最新值
            StoreSiteChangeLogger.onStoreSitesChanged(changes, remark, now)
        }
        storeSiteChangedExecutor.submit {
            storeSitesEventBus.fire(changes)
        }
    }

    fun onChargerChanged(charger: ChargerModel) {
        chargerChangedExecutors.submit { broadcastByWebSocket("charger-changed", charger) }
    }

    fun onLiftChanged(lift: LiftModel) {
        liftChangedExecutors.submit { broadcastByWebSocket("lift-changed", lift) }
    }

    fun onTransportOrderChanged(order: TransportOrder) {
        transportOrderChangedExecutors.submit {
            broadcastByWebSocket("transport-order-changed", TransportOrderOutput2.from(order))
        }
    }
}

class VehicleChangedEvent(val oldVehicle: Vehicle?, val newVehicle: Vehicle?)