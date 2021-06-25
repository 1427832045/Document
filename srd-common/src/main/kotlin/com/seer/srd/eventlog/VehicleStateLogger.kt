package com.seer.srd.eventlog

import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts.descending
import com.mongodb.client.model.Sorts.orderBy
import com.seer.srd.db.MongoDBManager
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.domain.PagingResult
import com.seer.srd.http.getPageNo
import com.seer.srd.http.getPageSize
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTransport
import com.seer.srd.route.service.VehicleService
import com.seer.srd.vehicle.Vehicle
import io.javalin.http.Context
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

object VehicleStateLogger {

    private val logger = LoggerFactory.getLogger(VehicleStateLogger::class.java)

    private val lastStates: MutableMap<String, VehicleStateTrace> = ConcurrentHashMap()

    @Synchronized
    fun statOnVehicleChanged(vehicle: Vehicle) {
        val c = collection<VehicleStateTrace>().apply {
            this.createIndex(Indexes.descending("endOn"))
            this.createIndex(Indexes.ascending("startOn"))
        }
        var lastState = lastStates[vehicle.name]
        if (lastState == null) {
            lastState =
                c.find(VehicleStateTrace::vehicle eq vehicle.name).sort(orderBy(descending("endOn"))).limit(1).first()
            if (lastState != null) lastStates[vehicle.name] = lastState
        }
        if (lastState == null || vehicle.state.name != lastState.toState) {

            val lastExecutingState = MongoDBManager.collection<VehicleStateTrace>().find(
                and(VehicleStateTrace::vehicle eq vehicle.name, VehicleStateTrace::transportOrder ne ""))
                .sort(orderBy(descending("endOn"))).limit(1).first()

//            logger.info("Vehicle ${vehicle.name} state changed from ${lastState?.toState} to ${vehicle.state}")
            // 增加transport和task字段，且park和recharge订单属于某个task(如果上一个transport属于某个task)
            val transport =
                if (vehicle.state.name == "EXECUTING") {
                    if (vehicle.transportOrder != lastExecutingState?.transportOrder) ""
                    else {
                        if (vehicle.transportOrder.isNullOrBlank()) logger.warn("executing without any order!!!!!")
                        vehicle.transportOrder ?: ""
                    }
                }
                else if (lastState?.toState !in listOf("IDLE", "EXECUTING")) ""
                else vehicle.transportOrder ?: if (lastState?.toState == "EXECUTING") lastState.transportOrder else ""
            val taskId = getTaskIdByTransportOrderNameAndLastExecutingState(transport, lastExecutingState)
            // 如果开始时间和结束时间不是同一个小时，就分成两条记录
            val now = Instant.now()
            if (lastState?.endOn != null) statHour(now, vehicle, lastState, taskId, transport)
            else {
              val newState = VehicleStateTrace(
                  vehicle = vehicle.name,
                  fromState = lastState?.toState ?: "",
                  toState = vehicle.state.name,
                  robotTask =
                  if (taskId.isBlank() && !vehicle.orderSequence.isNullOrBlank() && vehicle.orderSequence == lastState?.orderSequence)
                      lastState.robotTask else taskId,
                  transportOrder = transport,
                  orderSequence = vehicle.orderSequence ?: "",
                  duration = if (lastState == null) 0 else now.toEpochMilli() - lastState.endOn.toEpochMilli(),
                  startOn = lastState?.endOn ?: now,
                  endOn = now
              )
              c.insertOne(newState)
              lastStates[vehicle.name] = newState
            }
        }
    }

    fun handleListVehicleStateTrace(ctx: Context) {
        val pageNo = getPageNo(ctx)
        val pageSize = getPageSize(ctx)
        val c = collection<VehicleStateTrace>()
        val total = c.countDocuments()
        val page = c.find().sort(orderBy(descending("startOn"))).limit(pageSize).skip((pageNo - 1) * pageSize).toList()
        ctx.json(PagingResult(total, page, pageNo, pageSize))
    }

    @Synchronized
    fun forceStats() {
        val vehicles = VehicleService.listVehicles()
        for (vehicle in vehicles) {
            val c = collection<VehicleStateTrace>().apply { this.createIndex(Indexes.ascending("startOn")) }
            var lastState = lastStates[vehicle.name]
            if (lastState == null) {
                lastState =
                    c.find(VehicleStateTrace::vehicle eq vehicle.name).sort(orderBy(descending("endOn"))).limit(1).first()
                if (lastState != null) lastStates[vehicle.name] = lastState
            }
            if (lastState != null) {

                val lastExecutingState = c.find(
                    and(VehicleStateTrace::vehicle eq vehicle.name, VehicleStateTrace::transportOrder ne ""))
                    .sort(orderBy(descending("endOn"))).limit(1).first()

                val transport =
                    if (lastState.fromState == Vehicle.State.IDLE.name && lastState.toState == Vehicle.State.EXECUTING.name)
                        vehicle.transportOrder ?: ""
                    else if (lastState.toState != Vehicle.State.EXECUTING.name)
                        ""
                    else lastState.transportOrder

                val taskId = getTaskIdByTransportOrderNameAndLastExecutingState(transport, lastExecutingState)

                val now = Instant.now()
                statHour(now, vehicle, lastState, taskId, transport)
            }
        }
    }

    @Synchronized
    private fun getTaskIdByTransportOrderNameAndLastExecutingState(transport: String, lastExecutingState: VehicleStateTrace?): String {
      return when {
        transport.isBlank() -> ""
        else -> when {
          transport.startsWith("Park-") || transport.startsWith("Recharge-") -> if (lastExecutingState?.transportOrder != null) {
            when {
              lastExecutingState.transportOrder.startsWith("Park-") || lastExecutingState.transportOrder.startsWith("Recharge-") -> if (transport == lastExecutingState.transportOrder) lastExecutingState.robotTask
              else ""
              else -> lastExecutingState.robotTask
            }
          } else ""
          else -> MongoDBManager.collection<RobotTask>().findOne(RobotTask::transports.elemMatch(RobotTransport::routeOrderName eq transport))?.id ?: ""
        }
      }
    }

    @Synchronized
    private fun statHour(now: Instant, vehicle: Vehicle, lastState: VehicleStateTrace,
                         taskId: String, transport: String) {

        var tempTime = lastState.endOn

        val statList = mutableListOf<VehicleStateTrace>()

        if (now.isAfter(tempTime)) {

            val c = collection<VehicleStateTrace>()

            // 状态变化在同一天的同一个小时
            if (tempTime.truncatedTo(ChronoUnit.HOURS) == now.truncatedTo(ChronoUnit.HOURS)) {

                val newState = VehicleStateTrace(
                    vehicle = vehicle.name,
                    fromState = lastState.toState,
                    toState = vehicle.state.name,
                    robotTask =
                        if (taskId.isBlank() && !vehicle.orderSequence.isNullOrBlank() && vehicle.orderSequence == lastState.orderSequence)
                            lastState.robotTask else taskId,
                    transportOrder = transport,
                    orderSequence = vehicle.orderSequence ?: "",
                    duration = now.toEpochMilli() - lastState.endOn.toEpochMilli(),
                    startOn = lastState.endOn,
                    endOn = now
                )
                c.insertOne(newState)
                lastStates[vehicle.name] = newState

            // 状态变化在不在同一天的同一个小时
            } else {
                while (now.isAfter(tempTime)) {
                    val curLastState = lastStates[vehicle.name]
                    // 获取整小时
                    val tempHourTime = tempTime.truncatedTo(ChronoUnit.HOURS)


                    if (now.truncatedTo(ChronoUnit.HOURS) == tempHourTime) {
                        // 在同一天的一个小时内
                        val newState = VehicleStateTrace(
                            vehicle = vehicle.name,
                            fromState = curLastState?.toState ?: "",
                            toState = vehicle.state.name,
                            robotTask =
                                if (taskId.isBlank() && !vehicle.orderSequence.isNullOrBlank() && vehicle.orderSequence == curLastState?.orderSequence)
                                    curLastState.robotTask else taskId,
                            transportOrder = transport,
                            orderSequence = vehicle.orderSequence ?: "",
                            duration = now.toEpochMilli() - tempHourTime.toEpochMilli(),
                            startOn = tempHourTime,
                            endOn = now
                        )
                        statList.add(newState)
                        lastStates[vehicle.name] = newState
                        break

                    } else {
                        // 不在同一天的一个小时内,拆开
                        tempTime = tempTime.plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS)
                        val newState = VehicleStateTrace(
                            vehicle = vehicle.name,
                            fromState = curLastState?.toState ?: "",
                            toState = curLastState?.toState ?: "",
                            robotTask =
                                if (taskId.isBlank() && !vehicle.orderSequence.isNullOrBlank() && vehicle.orderSequence == curLastState?.orderSequence)
                                    curLastState.robotTask else taskId,
                            transportOrder = transport,
                            orderSequence = vehicle.orderSequence ?: "",
                            duration = if (curLastState?.endOn != null) tempTime.toEpochMilli() - curLastState.endOn.toEpochMilli() else 0,
                            startOn = curLastState?.endOn ?: tempTime,
                            endOn = tempTime
                        )
                        statList.add(newState)
                        lastStates[vehicle.name] = newState
                    }
                }
                c.insertMany(statList)
            }
        } else {
            logger.error("stats error end time is not after start time, start=$now, end=$now")
        }

    }

}

data class VehicleStateTrace(
    @BsonId var id: ObjectId = ObjectId(),
    var vehicle: String = "",
    var fromState: String = "",
    var toState: String = "",
    var duration: Long = 0,
    var robotTask: String = "",
    var transportOrder: String = "",
    var orderSequence: String = "",
    var startOn: Instant = Instant.now(),
    var endOn: Instant = Instant.now()
)