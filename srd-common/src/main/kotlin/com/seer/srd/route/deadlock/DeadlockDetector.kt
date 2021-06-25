package com.seer.srd.route.deadlock

import com.seer.srd.eventlog.EventLogLevel
import com.seer.srd.eventlog.SystemEvent
import com.seer.srd.eventlog.recordSystemEventLog
import com.seer.srd.route.dg.DGMaskManager
import com.seer.srd.route.domain.DGEdge
import com.seer.srd.route.domain.Deadlock
import com.seer.srd.route.service.VehicleService
import org.opentcs.data.order.TransportOrderState
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.concurrent.thread

data class DetectionGraphNode(
    val vehicleId: String,
    val positionId: String,
    val visited: Boolean,
    val predPositionIds: MutableList<String>,
    val succPositionIds: MutableList<String>
)

typealias DetectionGraph = HashMap<String, DetectionGraphNode>

private val LOG = LoggerFactory.getLogger("com.seer.srd.route.deadlock.detector")

fun detectDeadlock(): List<Deadlock> {
    // 遍历所有机器人
    val vehicles = com.seer.srd.route.service.VehicleService.listVehicles()
    val graph = DetectionGraph()
    
    for (v in vehicles) {
        // TODO 判断位置为空的方式
        if (v.currentPosition == null || v.nextPosition == null) {
            continue
        }
        
        val successorIndices = vehicles.mapIndexed { index, vehicle ->
            if (vehicle.currentPosition == v.nextPosition && vehicle.name != v.name) index else -1
        }.filter { it > -1 }
        val predecessorIndices = vehicles.mapIndexed { index, vehicle ->
            if (vehicle.nextPosition == v.currentPosition && vehicle.name != v.name) index else -1
        }.filter { it > -1 }
        
        if (successorIndices.isEmpty() || predecessorIndices.isEmpty()) continue
        if (!graph.containsKey(v.currentPosition)) {
            graph[v.currentPosition] = DetectionGraphNode(
                vehicleId = v.name, // TODO id 代替 name
                positionId = v.currentPosition,
                visited = false,
                predPositionIds = mutableListOf(),
                succPositionIds = mutableListOf(v.nextPosition)
            )
        } else {
            LOG.warn("The graph should not have key '{}}'", v.currentPosition)
            continue
        }
    }
    
    // 完善检测图
    for (key in graph.keys) {
        for (succ in graph[key]!!.succPositionIds) {
            LOG.debug("graph[succ]={}", graph[succ])
            val predPositionIds = graph[succ]!!.predPositionIds
            // 如果节点的后继点的前置点集中没有节点，就将节点加入到后继点的前置点中
            if (!predPositionIds.contains(key))
                graph[succ]!!.predPositionIds.add(key)
        }
    }
    
    // 反复删除入度为0的节点，最后剩下的一定在环里面，最简单暴力的方法
    while (true) {
        var canRemove = false
        var positionId2Remove = ""
        
        for (node in graph.values) {
            // 检查入度是否为0
            if (node.predPositionIds.size == 0) {
                canRemove = true
                positionId2Remove = node.positionId
            }
        }
        if (!canRemove) break
        
        // 删除点
        if (positionId2Remove == "") {
            LOG.error("Variable postionId2Remove should not be empty!")
            break
        }
        LOG.debug("Removing node from detection graph: {}", positionId2Remove)
        graph.remove(positionId2Remove)
        
        // 删除关联的边
        for (node in graph.values) {
            if (node.predPositionIds.contains(positionId2Remove)) {
                node.predPositionIds.remove(positionId2Remove)
            }
        }
    }
    
    // 剩下的点一定在环里
    if (graph.isEmpty()) return emptyList()
    
    val fountTime = Instant.now()
    val deadlocks = mutableListOf<Deadlock>()
    
    // 每个环就是一个死锁
    while (graph.isNotEmpty()) {
        val lockedVehicleNames = mutableListOf<String>()
        val lockedEdges = mutableListOf<DGEdge>()
        val circle = mutableListOf<String>()
        
        val ids = graph.keys.toList()
        var currentPositionId = ids[0]
        
        while (graph[currentPositionId] != null && !circle.contains(currentPositionId)) {
            
            lockedVehicleNames.add(graph[currentPositionId]!!.vehicleId)
            
            val nextPositionId = graph[currentPositionId]!!.succPositionIds[0]
            
            if (DGMaskManager.currentDG.edges[currentPositionId]!!.contains(nextPositionId))
                lockedEdges.add(DGEdge(fromSpot = currentPositionId, toSpot = nextPositionId)) // TODO 这里新建EDGE对象合理吗
            else if (currentPositionId != nextPositionId) {
                error("No Path from $currentPositionId to $nextPositionId")
            }
            
            circle.add(currentPositionId)
            currentPositionId = graph[currentPositionId]!!.succPositionIds[0]
        }
        
        circle.forEach { positionId -> graph.remove(positionId) }
        
        if (lockedVehicleNames.size == 1) continue
        // 拷贝一份Vehicle数据，修改系统中真实Vehicle的时候不修改这份拷贝
        val lockedVehicles = lockedVehicleNames.map { VehicleService.getVehicle(it).copy() }
        deadlocks.add(Deadlock(fountTime, lockedVehicles, lockedEdges))
    }
    
    if (deadlocks.size > 0) {
        LOG.error("Found deadlock: {}", deadlocks)
        deadlocks.forEach { d ->
            recordSystemEventLog("Deadlock", EventLogLevel.Error, SystemEvent.RouteDeadlock, d.lockedVehicles.joinToString())
        }
    }
    return deadlocks
}

fun detectAndSolveDeadlock() {
    thread(name = "deadlockHandler") {
        var interval = 2000L
        while (true) {
//            LOG.debug("Running deadlock service...")
    //列出所有处于未完成状态的任务，然后判断这些任务的状态是不是全部为结束态，包含 FAILED  UNROUTABLE FINISHED
//      有如下的情况
//      RAW,
//      ACTIVE,
//      DISPATCHABLE,
//      BEING_PROCESSED,
//      WITHDRAWN
    //若集合为空，默认返回true
            if (com.seer.srd.route.service.TransportOrderService.listUnfinishedOrders().all { it.state.isFinalState }) {
//            LOG.debug("No active order.")
//                interval *= 2
            } else {
                interval = 2000L
                val deadlocks = detectDeadlock()
                if (deadlocks.isNotEmpty()) {
                    LOG.debug("Found deadlocks, start solving")
                    if (deadlocks.isNotEmpty()) {
                        for (deadlock in deadlocks) {
                            solveDeadlock(deadlock)
                        }
                    }
                    LOG.debug("Finished solving deadlocks")
                }
            }
            Thread.sleep(interval)
        }
    }
}