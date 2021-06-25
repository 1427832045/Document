package com.seer.srd.route.service

import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.eventbus.EventBus.onTransportOrderChanged
import com.seer.srd.route.globalSyncObject
import com.seer.srd.route.routeConfig
import com.seer.srd.route.service.OrderSequenceService.getSequence
import com.seer.srd.route.service.OrderSequenceService.replaceSequence
import com.seer.srd.route.service.PlantModelService.getPlantModel
import org.litote.kmongo.*
import org.opentcs.access.to.order.DestinationCreationTO
import org.opentcs.access.to.order.TransportOrderCreationTO
import org.opentcs.data.ObjectConflictException
import org.opentcs.data.ObjectUnknownException
import org.opentcs.data.order.*
import org.slf4j.LoggerFactory
import java.lang.IllegalArgumentException
import java.time.Instant
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.toList

object TransportOrderService {
    private val LOG = LoggerFactory.getLogger("com.seer.srd")

    private val reportTransportOrderChanged = routeConfig.reportTransportOrderChanged

    var needRecover = true

    fun getOrderOrNull(name: String?): TransportOrder? {
        if (name.isNullOrBlank()) return null
        return collection<TransportOrder>().findOne(TransportOrder::name eq name)
    }

    fun getOrder(name: String): TransportOrder {
        return getOrderOrNull(name) ?: throw ObjectUnknownException("Unknown transport order '$name'.")
    }

    fun listUnfinishedOrders(): List<TransportOrder> {
        val states = getUnfinishedStates()
        return collection<TransportOrder>().find(TransportOrder::state `in` states).toList()
    }

    private fun addNewOrder(order: TransportOrder) {
        val c = collection<TransportOrder>()
        if (c.findOne(TransportOrder::name eq order.name) != null)
            throw ObjectConflictException("Transport order '${order.name}' is already existed.")
        c.insertOne(order)
    }

    private fun replaceOrder(order: TransportOrder): TransportOrder {
        collection<TransportOrder>().replaceOne(TransportOrder::name eq order.name, order)
        if (reportTransportOrderChanged) {
            onTransportOrderChanged(order)
        }
        return order
    }

    fun clear() {
        // do nothing
    }

    fun createTransportOrder(to: TransportOrderCreationTO): TransportOrder {
        synchronized(globalSyncObject) {
            val newOrder = TransportOrder(
                to.name,
                properties = to.properties,
                driveOrders = toDriveOrders(to.name, to.destinations),
                intendedVehicle = to.intendedVehicleName,
                category = to.category,
                deadline = to.deadline.toInstant(),
                isDispensable = to.isDispensable,
                wrappingSequence = to.wrappingSequence,
                dependencies = to.dependencyNames
            )
            LOG.debug("Creating new transportOrder: {}", to.name)
            addNewOrder(newOrder)
            if (newOrder.wrappingSequence != null) {
                val sequence = getSequence(newOrder.wrappingSequence)
                val orders = ArrayList(sequence.orders)
                orders.add(newOrder.name)
                replaceSequence(sequence.copy(orders = orders))
            }

            // Return the newly created transport order.
            return newOrder
        }
    }

    private fun toDriveOrders(transportOrderName: String, dests: List<DestinationCreationTO>): List<DriveOrder> {
        val result: MutableList<DriveOrder> = LinkedList()
        for (destTo in dests) {
            val pm = getPlantModel()
            if (pm.locations.containsKey(destTo.destLocationName)) {
                val destLoc = pm.locations[destTo.destLocationName]
                val destLocType = pm.locationTypes[destLoc!!.type] ?: throw IllegalArgumentException("Location type of $destLoc is null.")
                var isOperationAllowed = false
                for (curLink in destLoc.attachedLinks) {
                    if (Destination.OP_NOP == destTo.destOperation || curLink.hasAllowedOperation(destTo.destOperation)
                        || (curLink.allowedOperations.isEmpty()
                            && destLocType.isAllowedOperation(destTo.destOperation))) {
                        isOperationAllowed = true
                        break
                    }
                }
                require(isOperationAllowed) {
                    "The operation: " + destTo.destOperation + " is illegal at " + destTo.destLocationName
                }
            } else if (pm.points.containsKey(destTo.destLocationName)) {
                require(!(Destination.OP_MOVE != destTo.destOperation && Destination.OP_PARK != destTo.destOperation)) {
                    "Cannot go to Point: " + destTo.destLocationName + " with operation: " + destTo.destOperation
                }
            } else {
                throw ObjectUnknownException("Unknown destination '${destTo.destLocationName}'.")
            }
            result.add(
                DriveOrder(
                    Destination(
                        destTo.destLocationName, destTo.destLocationName, destTo.destOperation, destTo.properties
                    ),
                    transportOrderName
                )
            )
        }
        return result
    }

    fun updateTransportOrderDeadline(orderName: String, deadline: Long): TransportOrder {
        synchronized(globalSyncObject) {
            val order = getOrder(orderName)
            return replaceOrder(order.copy(deadline = Instant.ofEpochMilli(deadline)))
        }
    }

    /**
     * Updates a transport order's state.
     * Note that transport order states are intended to be manipulated by the dispatcher only.
     * Calling this method from any other parts of the kernel may result in undefined behaviour.
     */
    fun updateTransportOrderState(orderName: String, state: TransportOrderState): TransportOrder? {
        synchronized(globalSyncObject) {
            var order = getOrder(orderName)
            order = replaceOrder(order.copy(state = state))
            if (state == TransportOrderState.FINISHED) order = replaceOrder(order.copy(finishedTime = Instant.now()))
            return order
        }
    }

    fun updateProcessingVehicle(
        orderName: String,
        vehicleName: String?,
        driveOrders: List<DriveOrder>
    ): TransportOrder {
        synchronized(globalSyncObject) {
            var order = getOrder(orderName)
            if (vehicleName == null) {
                order = replaceOrder(order.copy(processingVehicle = null))
            } else {
                order = replaceOrder(
                    order.copy(
                        processingVehicle = vehicleName, driveOrders = driveOrders,
                        currentDriveOrderIndex = 0
                    )
                )
                if (order.currentDriveOrder != null) {
                    val newOrder = order.withCurrentDriveOrderState(DriveOrderState.TRAVELLING)
                    if (newOrder != null) order = replaceOrder(newOrder)
                }
            }
            return order
        }
    }

    fun updateTransportOrderDriveOrders(orderName: String, driveOrders: List<DriveOrder>): TransportOrder {
        synchronized(globalSyncObject) {
            var order = getOrder(orderName)
            order = replaceOrder(order.copy(driveOrders = driveOrders))
            return order
        }
    }

    fun updateTransportOrderNextDriveOrder(orderName: String): TransportOrder {
        synchronized(globalSyncObject) {
            var order = getOrder(orderName)
            // First, mark the current drive order as FINISHED and send an event.
            // Then, shift drive orders and send a second event.
            // Then, mark the current drive order as TRAVELLING and send another event.
            if (order.currentDriveOrder != null) {
                val newOrder1 = order.withCurrentDriveOrderState(DriveOrderState.FINISHED)
                if (newOrder1 != null) {
                    order = replaceOrder(newOrder1)
                    order = replaceOrder(order.copy(currentDriveOrderIndex = order.currentDriveOrderIndex + 1))
                }
                if (order.currentDriveOrder != null) {
                    val newOrder2 = order.withCurrentDriveOrderState(DriveOrderState.TRAVELLING)
                    if (newOrder2 != null) order = replaceOrder(newOrder2)
                }
            }
            return order
        }
    }

    // 慎用！！
    fun updateCurrentDriveOrderState(orderName: String, newState: DriveOrderState): TransportOrder {
        synchronized(globalSyncObject) {
            var order = getOrder(orderName)
            if (order.currentDriveOrder != null) {
                val newOrder1 = order.withCurrentDriveOrderState(newState)
                if (newOrder1 != null) {
                    order = replaceOrder(newOrder1)
                }
            }
            return order
        }
    }

    fun registerTransportOrderRejection(orderName: String, rejection: Rejection): TransportOrder {
        synchronized(globalSyncObject) {
            var order = getOrder(orderName)
            val rejections = ArrayList(order.rejections)
            rejections.add(rejection)
            order = replaceOrder(order.copy(rejections = rejections))
            return order
        }
    }

    fun failUnfinishedOrders() {
        collection<TransportOrder>().updateMany(TransportOrder::state `in` getUnfinishedStates(),
            set(TransportOrder::state setTo TransportOrderState.FAILED))
    }

    private fun getUnfinishedStates() = listOf(
        TransportOrderState.RAW,
        TransportOrderState.ACTIVE,
        TransportOrderState.DISPATCHABLE,
        TransportOrderState.BEING_PROCESSED,
        TransportOrderState.WITHDRAWN)
}