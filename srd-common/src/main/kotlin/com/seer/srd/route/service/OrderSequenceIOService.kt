package com.seer.srd.route.service

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.seer.srd.SystemError
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.domain.PagingResult
import com.seer.srd.domain.Property
import com.seer.srd.domain.propertyListToMap
import com.seer.srd.route.kernelExecutor
import com.seer.srd.route.service.TransportOrderService.createTransportOrder
import com.seer.srd.vehicle.Vehicle
import org.bson.conversions.Bson
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.litote.kmongo.regex
import org.opentcs.access.to.order.OrderSequenceCreationTO
import org.opentcs.components.kernel.services.DispatcherService
import org.opentcs.data.ObjectUnknownException
import org.opentcs.data.order.OrderSequence
import org.opentcs.kernel.getInjector
import org.slf4j.LoggerFactory
import java.lang.IllegalArgumentException
import java.time.Instant

object OrderSequenceIOService {

    private val logger = LoggerFactory.getLogger(OrderSequenceIOService::class.java)

    fun listOrderSequenceOutputs(query: ListOrderSequencesQuery): PagingResult<OrderSequenceOutput> {
        val c = collection<OrderSequence>()
        val filter = query.toFilter()
        val total = c.countDocuments(filter)
        val page = c.find(filter)
            .sort(Sorts.orderBy(Sorts.descending("createdOn")))
            .skip((query.pageNo - 1) * query.pageSize).limit(query.pageSize)
            .toList().map { OrderSequenceOutput.from(it) }
        return PagingResult(total, page, query.pageNo, query.pageSize)
    }

    fun getOrderSequenceOutputByName(name: String): OrderSequenceOutput? {
        val c = collection<OrderSequence>()
        val seq = c.findOne(OrderSequence::name eq name) ?: return null
        return OrderSequenceOutput.from(seq)
    }

    fun createOrderSequence(name: String, req: CreateOrderSequenceReq) {
        val sequenceTO = OrderSequenceCreationTO(name)
            .withCategory(req.category)
            .withIntendedVehicleName(req.intendedVehicle)
            .withFailureFatal(req.failureFatal)
            .withProperties(propertyListToMap(req.properties))

        if (req.transports.any { it.order.wrappingSequence != name }) {
            throw IllegalArgumentException("Wrapping sequence mismatch.")
        }

        val tos = req.transports
            .filter { it.order.wrappingSequence == name }
            .map { it.order.toTransportOrderCreationTO(it.name) }


        val injector = getInjector() ?: throw SystemError("No Injector")
        val dispatcherService = injector.getInstance(DispatcherService::class.java)

        kernelExecutor.submit {
            val orderSeq = OrderSequenceService.createOrderSequence(sequenceTO)
            tos.forEach { createTransportOrder(it) }
            if (req.complete == true) {
                OrderSequenceService.markOrderSequenceComplete(orderSeq.name)
            }
            dispatcherService.dispatch()
        }.get()
    }

    fun markOrderSequenceComplete(name: String) {
        val injector = getInjector() ?: throw SystemError("No Injector")
        val dispatcherService = injector.getInstance(DispatcherService::class.java)

        kernelExecutor.submit {
            val sequence = OrderSequenceService.getSequenceOrNull(name) ?: throw ObjectUnknownException("Unknown order sequence: '$name'")
            OrderSequenceService.markOrderSequenceComplete(sequence.name)
            dispatcherService.dispatch()
        }.get()
    }

    fun withdrawalSequenceByName(name: String, immediate: Boolean, disableVehicle: Boolean) {
        val injector = getInjector() ?: throw SystemError("No Injector")
        val dispatcherService = injector.getInstance(DispatcherService::class.java)

        kernelExecutor.submit {
            val sequence = OrderSequenceService.getSequenceOrNull(name) ?: throw ObjectUnknownException("Unknown order sequence: '$name'")
            val processingVehicle = sequence.processingVehicle
            if (disableVehicle && processingVehicle != null) {
                VehicleService.updateVehicleIntegrationLevel(
                    processingVehicle, Vehicle.IntegrationLevel.TO_BE_RESPECTED
                )
            }
            OrderSequenceService.markOrderSequenceComplete(sequence.name)
            sequence.orders.forEach {
                dispatcherService.withdrawByTransportOrder(it, immediate)
            }
        }
    }

}


class CreateOrderSequenceReq {
    // The order sequence's properties
    var properties: List<Property> = ArrayList()

    // The (optional) intended vehicle of the order sequence
    var intendedVehicle: String? = null

    // The (optional) category of the order sequence
    var category: String? = null

    // The (optional) failureFatal of the order sequence
    var failureFatal: Boolean? = null

    // The (optional) complete of the order sequence
    var complete: Boolean? = null

    // The transport orders
    var transports: List<TransportOrderItem> = ArrayList()
}

class TransportOrderItem(
    var name: String,
    var order: CreateTransportOrderReq
)

data class OrderSequenceOutput(
    val name: String,
    val createdOn: Instant,
    val orders: List<String>,
    val complete: Boolean,
    val finished: Boolean,
    val failureFatal: Boolean,
    val finishedIndex: Int?,
    val category: String?,
    val intendedVehicle: String?,
    val processingVehicle: String?
) {

    companion object {

        fun from(seq: OrderSequence): OrderSequenceOutput {
            return OrderSequenceOutput(
                seq.name,
                seq.createdOn,
                seq.orders.toList(),
                seq.complete,
                seq.finished,
                seq.failureFatal,
                seq.finishedIndex,
                seq.category,
                seq.intendedVehicle,
                seq.processingVehicle
            )
        }

    }
}


class ListOrderSequencesQuery(
    val pageNo: Int = 1,
    val pageSize: Int = 10,
    val namePrefix: String? = null,
    val complete: Boolean? = null,
    val failureFatal: Boolean? = null,
    val finished: Boolean? = null,
    val orderNamePrefix: String? = null,
    val category: String? = null,
    val intendedVehicle: String? = null,
    val processingVehicle: String? = null
) {
    fun toFilter(): Bson {
        val criteria = arrayListOf<Bson>()
        if (!intendedVehicle.isNullOrBlank()) criteria += OrderSequence::intendedVehicle eq intendedVehicle
        if (!processingVehicle.isNullOrBlank()) criteria += OrderSequence::processingVehicle eq processingVehicle
        if (!category.isNullOrBlank()) criteria += OrderSequence::category eq category
        if (complete != null) criteria += OrderSequence::complete eq complete
        if (failureFatal != null) criteria += OrderSequence::failureFatal eq failureFatal
        if (finished != null) criteria += OrderSequence::finished eq finished
        if (!namePrefix.isNullOrBlank()) criteria += OrderSequence::name regex namePrefix
        if (!orderNamePrefix.isNullOrBlank()) criteria += Filters.regex("orders", orderNamePrefix)
        return if (criteria.isEmpty()) org.bson.Document() else org.litote.kmongo.and(criteria)
    }
}
