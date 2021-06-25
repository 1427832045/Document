package com.seer.srd.route.service

import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import com.seer.srd.BusinessError
import com.seer.srd.SystemError
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.domain.PagingResult
import com.seer.srd.domain.Property
import com.seer.srd.domain.propertyListToMap
import com.seer.srd.route.kernelExecutor
import com.seer.srd.vehicle.Vehicle
import org.bson.conversions.Bson
import org.litote.kmongo.*
import org.opentcs.access.to.order.DestinationCreationTO
import org.opentcs.access.to.order.TransportOrderCreationTO
import org.opentcs.components.kernel.services.DispatcherService
import org.opentcs.data.ObjectConflictException
import org.opentcs.data.ObjectUnknownException
import org.opentcs.data.order.DriveOrder
import org.opentcs.data.order.DriveOrderState
import org.opentcs.data.order.TransportOrder
import org.opentcs.data.order.TransportOrderState
import org.opentcs.kernel.getInjector
import java.time.Instant
import kotlin.collections.toList

object TransportOrderIOService {
    
    fun listTransportOrderOutputsOld(query: ListTransportOrdersQuery): PagingResult<TransportOrderOutput> {
        val c = collection<TransportOrder>()
        val filter = query.toFilter()
        val total = c.countDocuments(filter)
        val page = c.find(filter).sort(Sorts.orderBy(Sorts.descending("creationTime")))
            .skip((query.pageNo - 1) * query.pageSize).limit(query.pageSize)
            .toList().map { TransportOrderOutput.from(it) }
        return PagingResult(total, page, query.pageNo, query.pageSize)
    }

    fun listTransportOrderOutputs(query: ListTransportOrdersQuery): PagingResult<TransportOrderOutput2> {
        val c = collection<TransportOrder>().apply {
            this.createIndex(Indexes.descending("creationTime"))
        }
        val filter = query.toFilter()
        val total = c.countDocuments(filter)
        val page = c.find(filter).sort(Sorts.orderBy(Sorts.descending("creationTime")))
            .skip((query.pageNo - 1) * query.pageSize).limit(query.pageSize)
            .toList().map { TransportOrderOutput2.from(it) }
        return PagingResult(total, page, query.pageNo, query.pageSize)
    }

    // 对内接口用老的 TransportOrderOutput
    fun getTransportOrderOutputByName2(name: String): TransportOrderOutput? {
        val c = collection<TransportOrder>()
        val order = c.findOne(TransportOrder::name eq name) ?: return null
        return TransportOrderOutput.from(order)
    }

    // 对外接口用新的 TransportOrderOutput2
    fun getTransportOrderOutputByName(name: String): TransportOrderOutput2? {
        val c = collection<TransportOrder>()
        val order = c.findOne(TransportOrder::name eq name) ?: throw ObjectUnknownException("Unknown transport order '$name'.")
        return TransportOrderOutput2.from(order)
    }
    
    fun createTransportOrder(name: String, req: CreateTransportOrderReq) {
        val order = TransportOrderService.getOrderOrNull(name)
        if (order != null) throw ObjectConflictException("Transport order '$name' is already existed.")
        if (!req.wrappingSequence.isNullOrBlank()) {
            try {
                val sequence = OrderSequenceService.getSequence(req.wrappingSequence!!)
                if (sequence.complete || sequence.finished)
                    throw BusinessError("sequence '${sequence.name}' is ${if (sequence.complete) "completed" else "finished"}")
            } catch (e: Exception) {
                throw BusinessError("${e.message}", e)
            }
        }
        val injector = getInjector() ?: throw SystemError("No Injector")
        val dispatcherService = injector.getInstance(DispatcherService::class.java)
        
        val to = req.toTransportOrderCreationTO(name)
        
        kernelExecutor.submit {
            TransportOrderService.createTransportOrder(to)
            dispatcherService.dispatch()
        }.get()
    }
    
    fun withdrawTransportOrder(name: String, immediate: Boolean, disableVehicle: Boolean) {
        val injector = getInjector() ?: throw SystemError("No Injector")
        val dispatcherService = injector.getInstance(DispatcherService::class.java)
        
        val order = TransportOrderService.getOrder(name)
        
        kernelExecutor.submit {
            val processingVehicle = order.processingVehicle
            if (disableVehicle && processingVehicle != null) {
                VehicleService.updateVehicleIntegrationLevel(
                    processingVehicle, Vehicle.IntegrationLevel.TO_BE_RESPECTED
                )
            }
            dispatcherService.withdrawByTransportOrder(order.name, immediate, disableVehicle)
        }
    }
    
    fun changeOrderDeadline(name: String, newDeadline: Instant) {
        val order = TransportOrderService.getOrder(name)
        
        kernelExecutor.submit {
            TransportOrderService.updateTransportOrderDeadline(order.name, newDeadline.toEpochMilli())
        }
    }
}

data class TransportOrderOutput(
    val name: String,
    val category: String?, // The category of the transport order.
    val creationTime: Instant,
    val deadline: Instant,
    val finishedTime: Instant?,
    val state: TransportOrderState?,
    val intendedVehicle: String?,
    val processingVehicle: String?,
    val destinations: List<DestinationOutput>,
    val dependencies: Set<String>,
    val wrappingSequence: String?, // The sequence this transport order may be in.
    val isDispensable: Boolean
) {
    companion object {
        
        fun from(order: TransportOrder): TransportOrderOutput {
            return TransportOrderOutput(
                order.name,
                order.category,
                order.creationTime,
                order.deadline,
                order.finishedTime,
                order.state,
                order.intendedVehicle,
                order.processingVehicle,
                order.driveOrders.map { DestinationOutput.from(it) },
                order.dependencies,
                order.wrappingSequence,
                order.isDispensable
            )
        }
        
    }
}

data class TransportOrderOutput2(
    val name: String,
    val category: String?, // The category of the transport order.
    val creationTime: Instant,
    val deadline: Instant,
    val finishedTime: Instant?,
    val state: TransportOrderState?,
    val intendedVehicle: String?,
    val processingVehicle: String?,
    val destinations: List<DestinationOutput2>,
    val dependencies: Set<String>,
    val wrappingSequence: String?, // The sequence this transport order may be in.
    val isDispensable: Boolean
) {
    companion object {

        fun from(order: TransportOrder): TransportOrderOutput2 {
            return TransportOrderOutput2(
                order.name,
                order.category,
                order.creationTime,
                order.deadline,
                order.finishedTime,
                order.state,
                order.intendedVehicle,
                order.processingVehicle,
                order.driveOrders.map { DestinationOutput2.from(it) },
                order.dependencies,
                order.wrappingSequence,
                order.isDispensable
            )
        }

    }
}

data class DestinationOutput(
    val locationName: String,
    val operation: String,
    val state: DriveOrderState,
    var properties: Map<String, String>
//    var properties: List<Map<String, String>>
) {
    
    companion object {
        
        fun from(driverOrder: DriveOrder): DestinationOutput {
            return DestinationOutput(
                driverOrder.destination.location,
                driverOrder.destination.operation,
                driverOrder.state,
                driverOrder.destination.properties
            )
        }
        
    }
}

data class DestinationOutput2(
    val locationName: String,
    val operation: String,
    val state: DriveOrderState,
//    var properties: Map<String, String>
    var properties: List<Map<String, String>>
) {

    companion object {

        fun from(driverOrder: DriveOrder): DestinationOutput2 {
            return DestinationOutput2(
                driverOrder.destination.location,
                driverOrder.destination.operation,
                driverOrder.state,
                toProperties(driverOrder.destination.properties)
            )
        }

        private fun toProperties(map: Map<String, String>): List<Map<String, String>> {
            val list: MutableList<Map<String, String>> = ArrayList()
            map.forEach {
                list.add(mapOf("key" to it.key, "value" to it.value))
            }
            return list
        }

    }
}

class CreateTransportOrderReq(
    var destinations: List<CreateDestinationReq>,
    var deadline: Instant? = null,
    var intendedVehicle: String? = null,
//    var dependencyNames: Set<String>? = null,
    var dependencies: Set<String>? = null,
    var properties: List<Property>? = null,
    var category: String? = null,
    var wrappingSequence: String? = null
) {
    
    fun toTransportOrderCreationTO(name: String): TransportOrderCreationTO {
        return TransportOrderCreationTO(name,
            destinations.map {
                DestinationCreationTO(it.locationName, it.operation).withProperties(propertyListToMap(it.properties))
            })
            .withIntendedVehicleName(if (intendedVehicle.isNullOrBlank()) null else intendedVehicle)
            .withDependencyNames(dependencies ?: emptySet())
            .withDeadline(deadline ?: Instant.now())
            .withProperties(propertyListToMap(properties ?: emptyList()))
            .withCategory(if (category.isNullOrBlank()) null else category)
            .withWrappingSequence(if (wrappingSequence.isNullOrBlank()) null else wrappingSequence)
    }
}

class CreateDestinationReq(
    val locationName: String,
    val operation: String,
    val properties: List<Property>
)

class ListTransportOrdersQuery(
    val pageNo: Int = 1,
    val pageSize: Int = 10,
    private val intendedVehicle: String? = null,
    private val processingVehicle: String? = null,
    private val category: String? = null,
    private val states: List<String>? = null,
    private val regexp: String? = null
) {
    fun toFilter(): Bson {
        val criteria = arrayListOf<Bson>()
        if (!intendedVehicle.isNullOrBlank()) criteria += TransportOrder::intendedVehicle eq intendedVehicle
        if (!processingVehicle.isNullOrBlank()) criteria += TransportOrder::processingVehicle eq processingVehicle
        if (!category.isNullOrBlank()) criteria += TransportOrder::category eq category
        if (!states.isNullOrEmpty()) criteria += TransportOrder::state `in` states.map { TransportOrderState.valueOf(it) }
        if (!regexp.isNullOrBlank()) criteria += TransportOrder::name regex regexp
        return if (criteria.isEmpty()) org.bson.Document() else and(criteria)
    }
}

