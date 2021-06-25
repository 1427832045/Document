package com.seer.srd.robottask

import com.seer.srd.CONFIG
import com.seer.srd.RouteOrderFailedError
import com.seer.srd.SyncRouteOrderError
import com.seer.srd.route.service.TransportOrderIOService.getTransportOrderOutputByName
import com.seer.srd.route.service.TransportOrderIOService.getTransportOrderOutputByName2
import com.seer.srd.route.service.TransportOrderOutput
import org.opentcs.data.order.DriveOrderState
import org.opentcs.data.order.TransportOrderState

fun waitTransportOrderFinish(task: RobotTask, transport: RobotTransport): TransportOrderOutput {
    val syncRouteOrderRetryDelay = CONFIG.syncRouteOrderRetryDelay
    while (true) {
        RobotTaskService.throwIfTaskAborted(task.id)
        val order = doQueryRouteOrder(transport.routeOrderName)
        if (isRouteOrderEnd(order)) return order
        Thread.sleep(syncRouteOrderRetryDelay)
    }
}

fun waitTransportDestinationFinish(task: RobotTask, transport: RobotTransport, destIndex: Int): TransportOrderOutput {
    val syncRouteOrderRetryDelay = CONFIG.syncRouteOrderRetryDelay
    while (true) {
        RobotTaskService.throwIfTaskAborted(task.id)
        val order = doQueryRouteOrder(transport.routeOrderName)
        if (isRouteOrderFailed(order)) throw RouteOrderFailedError()
        // 顺便查其他字段
        if (transport.processingRobot.isNullOrBlank() && !order.processingVehicle.isNullOrBlank()) {
            RobotTaskService.updateTransportProcessingRobot(order.processingVehicle, transport, task)
        }
        if (destIndex >= order.destinations.size)
            throw IllegalArgumentException("dest index $destIndex > order destinations ${order.destinations.size}")
        val dest = order.destinations[destIndex]
        if (isRouteDestinationEnd(dest.state.name)) return order
        Thread.sleep(syncRouteOrderRetryDelay)
    }
}


private fun doQueryRouteOrder(name: String): TransportOrderOutput {
    return getTransportOrderOutputByName2(name) ?: throw SyncRouteOrderError("No TransportOrder $name")
}

private fun isRouteOrderEnd(order: TransportOrderOutput): Boolean {
    val state = order.state
    return state == TransportOrderState.FINISHED || state == TransportOrderState.FAILED || state == TransportOrderState.UNROUTABLE
}

fun isRouteOrderSuccess(order: TransportOrderOutput): Boolean {
    val state = order.state
    return state == TransportOrderState.FINISHED
}

fun isRouteOrderFailed(order: TransportOrderOutput): Boolean {
    val state = order.state
    return state == TransportOrderState.FAILED || state == TransportOrderState.UNROUTABLE
}

fun isRouteDestinationEnd(routeState: String): Boolean {
    return routeState == DriveOrderState.FINISHED.name || routeState == DriveOrderState.FAILED.name
}