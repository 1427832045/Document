package com.seer.srd.recover

import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskService
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.route.globalSyncObject
import com.seer.srd.route.service.OrderSequenceService
import com.seer.srd.route.service.TransportOrderIOService
import com.seer.srd.route.service.TransportOrderService
import com.seer.srd.route.service.VehicleService
import com.seer.srd.storesite.StoreSiteService.releaseOwnedSites
import com.seer.srd.vehicle.VehicleManager
import com.seer.srd.vehicle.VehiclePersistable
import org.bson.Document
import org.litote.kmongo.lt
import org.slf4j.LoggerFactory

object RecoverAll {
    
    private val logger = LoggerFactory.getLogger(RecoverAll::class.java)
    
    @Synchronized
    fun recoverAll() {
        logger.info("recover all!!!!!")
        
        val tasks = collection<RobotTask>().find(RobotTask::state lt RobotTaskState.Success).toList()
        for (task in tasks) {
            try {
                RobotTaskService.abortTask(task.id)
                releaseOwnedSites(task.id, "recoverAll")
            } catch (e: Exception) {
                logger.error("abort task ${task.id}", e)
            }
        }
        
        collection<VehiclePersistable>().deleteMany(Document())
        
        val sequences = ArrayList(OrderSequenceService.listNotCompleteOrderSequences())
        logger.info("complete all order sequences ${sequences.size}")
        for (seq in sequences) {
            try {
                OrderSequenceService.markOrderSequenceComplete(seq.name)
            } catch (e: Exception) {
                logger.error("complete seq ${seq.name}", e)
            }
        }
        
        val orders = ArrayList(TransportOrderService.listUnfinishedOrders())
            .filter { order -> !order.state.isFinalState }
        logger.info("withdraw all orders ${orders.size}")
        for (order in orders) {
            try {
                TransportOrderIOService.withdrawTransportOrder(order.name, immediate = true, disableVehicle = true)
            } catch (e: Exception) {
                logger.error("withdraw order ${order.name}", e)
            }
        }
        
        logger.info("withdraw all vehicles")
        val vehicles = ArrayList(VehicleService.listVehicles())
        for (vehicle in vehicles) {
            try {
                VehicleManager.withdrawByVehicle(vehicle.name, true, disableVehicle = true)
            } catch (e: Exception) {
                logger.error("withdraw vehicle ${vehicle.name}", e)
            }
            synchronized(globalSyncObject) {
                VehicleService.setVehicleOrderSequence(vehicle.name, null)
                VehicleService.setVehicleTransportOrder(vehicle.name, null)
            }
        }
        
        val sequences2 = ArrayList(OrderSequenceService.listNotFinishedOrderSequences())
        logger.info("finish all order sequences ${sequences2.size}")
        for (seq in sequences2) {
            try {
                OrderSequenceService.markOrderSequenceFinished(seq.name)
            } catch (e: Exception) {
                logger.error("finish seq ${seq.name}", e)
            }
        }
    }
    
}