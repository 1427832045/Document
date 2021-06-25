package com.seer.srd.route.deadlock

import com.seer.srd.route.DeadlockState.solvingDeadlock
import com.seer.srd.route.deadlock.searchalgorithms.PhasedPathTable
import com.seer.srd.route.deadlock.searchalgorithms.pathTableSearchAlgorithms
import com.seer.srd.route.dg.DGMaskManager
import com.seer.srd.route.dg.getMask4Deadlock
import com.seer.srd.route.domain.DGEdge
import com.seer.srd.route.domain.Deadlock
import com.seer.srd.route.service.TransportOrderService
import com.seer.srd.route.service.VehicleService
import com.seer.srd.vehicle.Vehicle
import com.seer.srd.vehicle.VehicleManager.pauseAllVehicles
import com.seer.srd.vehicle.driver.VehicleDriverManager
import org.opentcs.access.to.order.DestinationCreationTO
import org.opentcs.access.to.order.TransportOrderCreationTO
import org.opentcs.data.order.Destination
import org.opentcs.data.order.Destination.Companion.OP_MOVE
import org.opentcs.data.order.DriveOrder
import org.opentcs.data.order.Route
import org.opentcs.data.order.Step
import org.opentcs.kernel.getInjector
import org.opentcs.kernel.vehicles.DefaultVehicleController
import org.opentcs.strategies.basic.dispatching.TransportOrderUtil
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.concurrent.thread

private val LOG = LoggerFactory.getLogger("com.seer.srd.route.deadlock.solver")

// TODO 到底什么样的死锁能解？
fun canSolve(deadlock: Deadlock) = deadlock.lockedVehicles.size < DGMaskManager.currentDG.vertices.size

// TODO 更改死锁类型
enum class DeadlockType {
    Exchange,
    Cycle,
    Other
}

//fun identifyDeadlockType(deadlock: Deadlock): DeadlockType {
//    if (deadlock.lockedVehicles.size == 2) return DeadlockType.Exchange
//    return if (canInsertPointInDeadlockOnEdge(deadlock)) DeadlockType.SimpleCycle else DeadlockType.ComplexCycle
//}
fun identifyDeadlockType(deadlock: Deadlock): DeadlockType {
    if (deadlock.lockedVehicles.size == 2) return DeadlockType.Exchange
    return DeadlockType.Cycle
}

fun canInsertPointInDeadlockOnEdge(deadlock: Deadlock): Boolean {
    // TODO 判断能否在一条边上增加临时点
    return deadlock.edges.any { canInsertPointInDeadlockOnEdge(it) }
}

fun canInsertPointInDeadlockOnEdge(edge: DGEdge): Boolean {
    return edge.fromSpot.isNotBlank() && edge.toSpot.isNotBlank()
}

fun positionList2DriveOrder(pl: List<String>): DriveOrder {
    val steps = mutableListOf<Step>()
    for (i in 0 until pl.size - 1) {
        steps.add(Step("${pl[i]} --- ${pl[i + 1]}", pl[i], pl[i + 1], Vehicle.Orientation.FORWARD, i))
    }
    return DriveOrder(
        destination = Destination(pl.last(), pl.last(), OP_MOVE),
        route = Route(steps, 0)
    )
}

fun executePhasedPathTable(ppt: PhasedPathTable, vehicles: List<Vehicle>) {
    val vehicleNames = vehicles.map { it.name }
    for (phaseName in ppt.keys) { // TODO 用String会不会导致顺序错乱，比如phase9 排在phase89后面？
        // 清空controller的状态信息，为后面assignOrder准备
        vehicleNames.forEach { vehicleName ->
            val controller = VehicleDriverManager.getVehicleControllerOrNull(vehicleName) ?: return@forEach
            if (controller is DefaultVehicleController) {
                controller.clearDriveOrder()
                controller.clearCommandQueue()
            }
        }
        
        LOG.debug("Executing phase: {}", phaseName)
        val phase = ppt[phaseName] ?: error("Empty phase")
        val orderNames = mutableMapOf<String, String>()
        
        phase.keys.forEach { vehicleName ->
            // 让需要移动的车子继续运行
            val adapter = VehicleDriverManager.getVehicleCommAdapterOrNull(vehicleName) ?: return@forEach
            adapter.setVehiclePaused(false)
            
        }
        // assign orders
        // TODO 临时transport order要记录到mongoDB里面吗？
        phase.keys.forEach { vehicleName ->
            val orderName = "deadlock-solving-$phaseName-${Instant.now()}"
            orderNames[vehicleName] = orderName
            if (phase[vehicleName] == null) return@forEach
            
            TransportOrderService.createTransportOrder(TransportOrderCreationTO(orderName,
                listOf(DestinationCreationTO(phase[vehicleName]!!.last(), OP_MOVE))
            ))
            
            val order = TransportOrderService.getOrder(orderName)
            LOG.info("Generated order: {}", order)
            val injector = getInjector() ?: error("Failed to get injector.")
            // construct route
            val driveOrders = listOf(positionList2DriveOrder(phase[vehicleName]!!))
            injector.getInstance(TransportOrderUtil::class.java).assignTransportOrder(
                VehicleService.getVehicle(vehicleName), order, driveOrders
            )
        }
        // 轮询是最差的办法
        // 有没有类似Promise.all的东西？
        // TODO 使用condition variable重写
        /*会不会有这样的问题：
        * 在00:00.000(分:秒.毫秒）时刻，完成了订单，在 00:01.000才会再次检查是否完成订单，而调度在 00:00.500发送了park订单*/
        thread {
            while (phase.keys.any { vehicleName ->
                    !TransportOrderService.getOrder(orderNames[vehicleName]!!).state.isFinalState
                }) {
                Thread.sleep(1000L)
            }
        }.join()
        
        LOG.debug("Finished {}:{}", phaseName, ppt[phaseName])
        // pause again
        // TODO 没必要停下所有的车
        pauseAllVehicles(true)
    }
}

// TODO 解死锁要不要显示通知？
/** 解死锁的结束条件应该怎么设置？
 * 目前的结束条件是所有死锁车辆都移动一步就视为解死锁成功
 * 待选：死锁车辆的路径不再出现交叉点视为解锁成功
 */
fun solveDeadlock(deadlock: Deadlock) {
    LOG.debug("Start solving deadlock: {}", deadlock)
    solvingDeadlock = true
    
    val lockedVehicles = deadlock.lockedVehicles
    lockedVehicles.forEach { vehicle ->
        if (vehicle.transportOrder == null) {
            LOG.error("Deadlocked vehicle '{}' does not have a transport order.", vehicle.name)
            return
        }
    }
    
    // 停止机器人
    pauseAllVehicles(true)
    // 保存之前的transportOrder，后面恢复用
    val previousOrder = lockedVehicles.map { it.name to it.transportOrder!! }.toMap()
    val previousOrderSequence = lockedVehicles.map {
        it.name to it.orderSequence
    }.toMap()
    val previousDriveOrder = lockedVehicles.map {
        val controller = (VehicleDriverManager.getVehicleControllerOrNull(it.name)
            ?: error("No drive order")) as DefaultVehicleController
        it.name to controller.currentDriveOrder
    }.toMap()
    lockedVehicles.forEach {
        VehicleService.setVehicleTransportOrder(it.name, null)
        VehicleService.setVehicleOrderSequence(it.name, null)
    }
    // 这里为什么需要强转？之前不是判断过这个字段了吗
    LOG.debug("Transport orders prior to deadlock: {}", previousOrder.toString())
    val deadlockType = identifyDeadlockType(deadlock)
    LOG.debug("Found deadlock: ${deadlock}, type: ${deadlockType.name}")
    // mask DG
    DGMaskManager.maskDG(getMask4Deadlock(deadlockType, deadlock))
    
    // 取得对应的搜索方法
    // TODO 使用可以在yaml里配置的一组搜索算法，然后比较优劣，选择最好的解
    val searchAlgorithm = pathTableSearchAlgorithms[deadlockType]!!["naive"] ?: error("No algorithm")
    LOG.debug("Start searching for phased path table...")
    val phasedPathTable = searchAlgorithm(deadlock) // TODO 没搜到路径怎么办
    LOG.debug("Found phased path table: {}", phasedPathTable)
    if (phasedPathTable.isEmpty()) {
        LOG.error("Unable to solve the deadlock, no paths.")
        return
    }
    // 执行
    executePhasedPathTable(phasedPathTable, deadlock.lockedVehicles)
    // 恢复任务
    /* 这里不需要先清空controller里面的状态再assign，
    * 因为死锁解开的条件就是死锁中的所有车子走到下一个点，
    * TODO cycle解锁过程中有跳步的情况，观察并处理引发的问题
    * 路径信息没有改变，相当于车子只是走到了下一个点,所以直接setVehicleTransportOrder*/
    previousOrder.keys.forEach { vehicleName ->
        VehicleService.setVehicleTransportOrder(vehicleName, (previousOrder[vehicleName]
            ?: error("No previous order.")))
    }
    previousOrder.keys.forEach {
        VehicleService.setVehicleOrderSequence(it, previousOrderSequence[it])
    }
    lockedVehicles.forEach {
        // TODO 这里打印出来的commandsSent和futureCommands都为空，但是实际上不是空的
        VehicleService.setVehicleProcState(it.name, Vehicle.ProcState.PROCESSING_ORDER)
        val controller = VehicleDriverManager.getVehicleControllerOrNull(it.name) ?: return@forEach
        if (controller is DefaultVehicleController) {
//            LOG.debug("Controller commands sent: {}", controller.commandsSent)
            val oldRoute = previousDriveOrder[it.name]!!.route!!.copy()
//            LOG.debug("Old Route: {}", oldRoute)
            val currentPosition = VehicleService.getVehicle(it.name).currentPosition!!
//            LOG.debug("Current Position: {}", currentPosition)
            val currentIndex = oldRoute.steps.indexOfFirst { step -> step.destinationPoint == currentPosition }
            if (currentIndex == -1){
                LOG.info("Vehicle={}, oldRoute={}, currentPosition={}",it.name, oldRoute, currentPosition)
            }
//            LOG.debug("Current index: {}", currentIndex)
            val size = oldRoute.steps.size
            val newSteps = oldRoute.steps.subList(currentIndex, size).mapIndexed { index, step -> step.copy(routeIndex = index) }
            val newRoute = oldRoute.copy(steps = newSteps)
//            LOG.debug("Old Route: {}, new Route: {}", oldRoute, newRoute)
//            LOG.debug("CommandsSent: {}", controller.commandsSent)
            controller.clearCommandQueue()
            val newDriveOrder = previousDriveOrder[it.name]!!.copy(route = newRoute)
//            LOG.debug("newDriveOrder={}", newDriveOrder)
            controller.setDriveOrder(newDriveOrder, TransportOrderService.getOrder(previousOrder[it.name]!!).properties)
        }
    }
    DGMaskManager.undoLastSetOfMasks()
    pauseAllVehicles(false)
    solvingDeadlock = false
}