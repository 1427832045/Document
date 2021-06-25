package com.seer.srd.route.service

import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.route.globalSyncObject
import com.seer.srd.route.service.VehicleService.getVehicle
import com.seer.srd.route.service.VehicleService.setVehicleOrderSequence
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.litote.kmongo.set
import org.litote.kmongo.setTo
import org.opentcs.access.to.order.OrderSequenceCreationTO
import org.opentcs.data.ObjectConflictException
import org.opentcs.data.ObjectUnknownException
import org.opentcs.data.order.OrderSequence
import org.slf4j.LoggerFactory
import java.time.Instant

object OrderSequenceService {
    
    private val logger = LoggerFactory.getLogger(OrderSequenceService::class.java)
    
    fun getSequenceOrNull(name: String): OrderSequence? {
        return collection<OrderSequence>().findOne(OrderSequence::name eq name)
    }
    
    fun getSequence(name: String): OrderSequence {
        return getSequenceOrNull(name) ?: throw ObjectUnknownException("Unknown order sequence: '$name'")
    }
    
    fun listNotCompleteOrderSequences(): List<OrderSequence> {
        return collection<OrderSequence>().find(OrderSequence::complete eq false).toList()
    }
    
    fun listNotFinishedOrderSequences(): List<OrderSequence> {
        return collection<OrderSequence>().find(OrderSequence::finished eq false).toList()
    }
    
    private fun addNewSeq(seq: OrderSequence) {
        val c = collection<OrderSequence>()
        if (c.findOne(OrderSequence::name eq seq.name) != null)
            throw ObjectConflictException("Order sequence '${seq.name}' is already existed.")
        c.insertOne(seq)
    }
    
    fun replaceSequence(seq: OrderSequence): OrderSequence {
        collection<OrderSequence>().replaceOne(OrderSequence::name eq seq.name, seq)
        return seq
    }
    
    fun clear() {
        // do nothing
    }
    
    fun createOrderSequence(to: OrderSequenceCreationTO): OrderSequence {
        synchronized(globalSyncObject) {
            val newSequence = OrderSequence(
                to.name,  Instant.now(), to.properties,
                category = to.category, intendedVehicle = to.intendedVehicleName, failureFatal = to.isFailureFatal
            )
            addNewSeq(newSequence)
            // Return the newly created transport order.
            return newSequence
        }
    }
    
    fun updateOrderSequenceFinishedIndex(seqName: String, index: Int): OrderSequence {
        synchronized(globalSyncObject) {
            var sequence = getSequence(seqName)
            sequence = replaceSequence(sequence.copy(finishedIndex = index))
            return sequence
        }
    }
    
    fun markOrderSequenceComplete(seqName: String) {
        synchronized(globalSyncObject) {
            val seq = getSequenceOrNull(seqName)
            if (seq == null) {
                logger.warn("mark order seq complete, but seq is null, name=$seqName")
                return
            }
            // Make sure we don't execute this if the sequence is already marked as finished, as that
            // would make it possible to trigger disposition of a vehicle at any given moment.
            if (seq.complete) return
            setOrderSequenceComplete(seqName)
            // If there aren't any transport orders left to be processed as part of the sequence, mark
            // it as finished, too.
            if (seq.nextUnfinishedOrder == null) {
                setOrderSequenceFinished(seqName)
                // If the sequence was being processed by a vehicle, clear its back reference to the
                // sequence to make it available again and dispatch it.
                if (seq.processingVehicle != null) {
                    val vehicle = getVehicle(seq.processingVehicle)
                    setVehicleOrderSequence(vehicle.name, null)
                }
            }
        }
    }
    
    private fun setOrderSequenceFinished(seqName: String): OrderSequence {
        var sequence = getSequence(seqName)
        sequence = replaceSequence(sequence.copy(finished = true))
        return sequence
    }
    
    fun markOrderSequenceFinished(seqName: String) {
        synchronized(globalSyncObject) {
            val seq = getSequenceOrNull(seqName)
            if (seq == null) {
                logger.warn("mark order seq finished, but seq is null, name=$seqName")
                return
            }
            // Make sure we don't execute this if the sequence is already marked as finished, as that
            // would make it possible to trigger disposition of a vehicle at any given moment.
            if (seq.finished) return
            setOrderSequenceFinished(seqName)
            // If the sequence was being processed by a vehicle, clear its back reference to the sequence
            // to make it available again and dispatch it.
            if (seq.processingVehicle != null) {
                val vehicle = getVehicle(seq.processingVehicle)
                setVehicleOrderSequence(vehicle.name, null)
            }
        }
    }
    
    private fun setOrderSequenceComplete(seqName: String): OrderSequence {
        var sequence = getSequence(seqName)
        sequence = replaceSequence(sequence.copy(complete = true))
        return sequence
    }
    
    fun updateOrderSequenceProcessingVehicle(seqName: String, vehicleName: String?) {
        synchronized(globalSyncObject) {
            setOrderSequenceProcessingVehicle(seqName, vehicleName)
        }
    }
    
    private fun setOrderSequenceProcessingVehicle(seqName: String, vehicleName: String?): OrderSequence? {
        var sequence = getSequence(seqName)
        sequence = if (vehicleName == null) {
            replaceSequence(sequence.copy(processingVehicle = null))
        } else {
            replaceSequence(sequence.copy(processingVehicle = vehicleName))
        }
        return sequence
    }
    
    fun finishUnfinishedSequences() {
        val c = collection<OrderSequence>()
        c.updateMany(OrderSequence::finished eq false, set(OrderSequence::finished setTo true))
    }
}
