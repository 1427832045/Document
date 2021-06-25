package com.seer.srd.vehicle

import com.seer.srd.route.service.OrderSequenceService
import com.seer.srd.route.service.TransportOrderService
import com.seer.srd.route.service.VehicleService

fun rematchTransportOrders() {
    TransportOrderService.listUnfinishedOrders().filter { it.processingVehicle != null }.forEach {
        VehicleService.updateVehicleTransportOrder(it.processingVehicle!!, it.name)
        VehicleService.updateVehicleProcState(it.processingVehicle, Vehicle.ProcState.PROCESSING_ORDER)
    }
}

fun rematchOrderSequences() {
    OrderSequenceService.listNotFinishedOrderSequences().filter { it.processingVehicle != null }.forEach {
        VehicleService.updateVehicleOrderSequence(it.processingVehicle!!, it.name)
    }
}