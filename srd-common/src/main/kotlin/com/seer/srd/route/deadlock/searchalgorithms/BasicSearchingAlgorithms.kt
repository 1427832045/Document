package com.seer.srd.route.deadlock.searchalgorithms

import com.seer.srd.SystemError
import com.seer.srd.route.deadlock.DeadlockType
import com.seer.srd.route.dg.*
import com.seer.srd.route.domain.DGEdge
import com.seer.srd.route.domain.Deadlock
import com.seer.srd.route.service.TransportOrderService
import com.seer.srd.route.service.VehicleService
import org.opentcs.components.kernel.services.SchedulerService
import org.opentcs.kernel.getInjector
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.math.max

private val LOG = LoggerFactory.getLogger("com.seer.srd.route.deadlock.search")


fun naiveSearch4ExchangeDeadlocks(deadlock: Deadlock): PhasedPathTable {
    val vertexIds4Dodging = mutableListOf<String>()
    var branchPositionId = ""
    
    // branchPosition: 分岔口，和两个以上死锁以外的点有双向连接，本身可以在死锁内
    // dodgePosition: 和branchPosition相连，且不在从branchPosition到死锁位置路段上的两个点
    
    val searchHistory = listOf(mutableListOf<String>(), mutableListOf())
    
    val startVertices = deadlock.lockedVehicles.mapNotNull { it.currentPosition }
    val targetVertices = deadlock.lockedVehicles.mapNotNull { it.nextPosition }
    
    assert(startVertices.size == 2) { "Wrong number of startVertices in Exchange deadlock!" }
    assert(targetVertices.size == 2) { "Wrong number of targetVertices in Exchange deadlock!" }
    
    // 在两个方向上的搜索
    val searchQs = listOf(LinkedList(mutableListOf(startVertices[0])), LinkedList(mutableListOf(startVertices[1])))
    var direction = -1
    val formerVertices = mutableMapOf<String, String>()
    
    outer@ while (true) {
        // 尝试两个方向
        for (dir in 0..1) {
            // 选一个没有检查过的点
            val vertex2Examine = searchQs[dir].pop() ?: break@outer
            
            LOG.debug("vertex2Examine: {}", vertex2Examine)
            searchHistory[dir].add(vertex2Examine)
            
            val verticesConnectedByUndirectedEdges = mutableListOf<String>()
            
            // 检查这个节点的双向连接
            if (DGMaskManager.currentDG.edges[vertex2Examine] != null) {
                for (toVertex in DGMaskManager.currentDG.edges[vertex2Examine]!!) {
                    // 和vertex2Examine双向连接，且不在startVertices或者searchHistory里面
                    if (DGMaskManager.currentDG.edges[toVertex]!!.contains(vertex2Examine)
                        && !startVertices.contains(toVertex)
                        && !searchHistory[dir].contains(toVertex)
                    ) {
                        verticesConnectedByUndirectedEdges.add(toVertex)
                        formerVertices[toVertex] = vertex2Examine // 用于重构路径
                    }
                }
            }
            
            when {
                verticesConnectedByUndirectedEdges.size > 1 -> {
                    vertexIds4Dodging.addAll(verticesConnectedByUndirectedEdges)
                    direction = dir
                    branchPositionId = vertex2Examine
                    LOG.debug(
                        "Found branching vertex: {} direction: {} -> {} dodging vertices: {}"
                        , branchPositionId, startVertices[direction], startVertices[1 - direction], vertexIds4Dodging
                    )
                    break@outer
                }
                verticesConnectedByUndirectedEdges.size == 1 -> {
                    searchQs[dir].add(verticesConnectedByUndirectedEdges[0])
                }
                else -> {
                    error("wrong verticesConnectedByUndirectedEdges.size: ${verticesConnectedByUndirectedEdges.size}")
                }
            }
        }
    } // end outer@while
    
    if (vertexIds4Dodging.isEmpty() || direction == -1 || branchPositionId == "") return mutableMapOf()
    
    // 建立路径
    val path2BranchingVertex = mutableListOf<String>()
    var tmpVertexId: String
    for (key in formerVertices.keys) {
        LOG.debug("{} <- {}", key, formerVertices[key])
    }
    // todo 用generateSequence简化这5行代码
    tmpVertexId = branchPositionId
    do {
        path2BranchingVertex.add(tmpVertexId)
        tmpVertexId = formerVertices[tmpVertexId] ?: ""
    } while (tmpVertexId != "")
    
    LOG.debug("Dodging at: {}", vertexIds4Dodging)
    LOG.debug("Path: {}", path2BranchingVertex)
    
    // 路径分阶段
    val vehicle1 = deadlock.lockedVehicles[direction].name
    val vehicle2 = deadlock.lockedVehicles[1 - direction].name
    val phase1 = mapOf(
        vehicle1 to
//            listOf(startVertices[direction]) +
            path2BranchingVertex.reversed() + vertexIds4Dodging[0]
    )
    val phase2 = mapOf(
        vehicle2 to
            listOf(startVertices[1 - direction]) + path2BranchingVertex.reversed() + vertexIds4Dodging[1]
    )
    val phase3 = mapOf(
        vehicle1 to
            listOf(vertexIds4Dodging[0]) + path2BranchingVertex + targetVertices[direction]
    )
    val phase4 = mapOf(
        vehicle2 to
            listOf(vertexIds4Dodging[1]) + path2BranchingVertex
//            + targetVertices[1 - direction]
    )
    
    return mapOf("phase1" to phase1, "phase2" to phase2, "phase3" to phase3, "phase4" to phase4)
}

fun pathTableGeneration4SimpleCycleDeadlocksUsingMiddlePoints(deadlock: Deadlock): PhasedPathTable {
    val vehicles = deadlock.lockedVehicles
    // 使用路径中间加临时点的方法解锁
    // 先找到已经被删除的边，被删除的边上都是加了点的
    val changedEdges =
        deadlock.edges.filterNot { DGMaskManager.currentDG.edges[it.fromSpot]?.contains(it.toSpot) ?: false }
    LOG.debug("Edges changed: {}", changedEdges)
    if (changedEdges.isEmpty()) {
        LOG.error("No edge is changed!")
        error("No edge is changed!")
    }
    // 找到能移动的车子
    val movableVehicles = vehicles.filter { v ->
        changedEdges.map { it.fromSpot }.contains(v.currentPosition)
    }
    // 把能动的车子移动到新加的点上，路线是changedEdge.fromSpot -> getMiddleVertexName(changedEdge)
    val phase1 = mutableMapOf<String, List<String>>()
    movableVehicles.forEach { v ->
        phase1[v.name] = listOf(getMiddleVertexName(changedEdges.first { it.fromSpot == v.currentPosition }))
    }
    
    val result = mutableMapOf<String, MutableMap<String, List<String>>>()
    result["phase1"] = phase1
    
    // 添加了中间点的边将环分成几段，每段分别处理，然后合并路径表
    val segments = generateSequence(
        Pair(listOf<List<String>>(), 0), { it ->
        val startVertex = changedEdges[it.second].toSpot
        val currentSegment = mutableListOf(startVertex)
        val endVertex = changedEdges[it.second + 1 % changedEdges.size].fromSpot
        var tmpV = startVertex
        while (tmpV != endVertex) {
            val nextV = deadlock.edges.find { e -> e.fromSpot == tmpV }!!.toSpot
            currentSegment.add(nextV)
            tmpV = nextV
        }
        if (it.second + 1 == changedEdges.size) null else
            Pair(it.first + listOf(currentSegment), it.second + 1)
    }
    ).last().first
    
    var finalPhaseNumber = 2
    segments.forEach { segment ->
        val numPhases = segment.size - 1
        finalPhaseNumber = max(numPhases + 2, finalPhaseNumber)
        // 除了最后一辆车（最后一辆车已经被移到新加的中间点上去了），每辆车一个phase，目标点就是segment里面的下个点
        // phase编号从2开始
        for (i in 0 until numPhases) {
            val phaseName = "phase${2 + i}"
            if (!result.containsKey(phaseName)) {
                result[phaseName] = mutableMapOf()
            }
            
            // 当前点上面的车
            val vehicle = vehicles.find { it.currentPosition == segment[i] }!!.name
            result[phaseName]!![vehicle] = listOf(segment[i], segment[i + 1])
        }
    }
    
    // 最后，把中间点上的车开到下一个点
    val finalPhase = mutableMapOf<String, List<String>>()
    movableVehicles.forEach { v ->
        finalPhase[v.name] = listOf(changedEdges.first { it.fromSpot == v.currentPosition }.toSpot)
    }
    result["phase$finalPhaseNumber"] = finalPhase
    return result
}

// 不在路径中间加点，而是在环外面找一个点避让，既可以用在SimpleCycle上，也可以用在ComplexCycle上
fun pathTableGeneration4CycleDeadlocks(deadlock: Deadlock): PhasedPathTable {
    // TODO 车子的目标点如果就在环上，那么没办法用这个解死锁，此时应该抛出异常
    // 找到环外面一个没有被占用的点，这个点和环上点的最短距离肯定是一条边
    val lockedVertices = deadlock.edges.map { listOf(it.fromSpot, it.toSpot) }.flatten().toSet()
    val lockedVehicles = deadlock.lockedVehicles
    LOG.debug("Locked vertices: {}", lockedVertices)
    // 临时状态，用于在规划路径时记录中间状态
    val temporaryState = mutableMapOf<String, String>()// vehicle.name : vehicle.position
    lockedVehicles.forEach {
        temporaryState[it.name] = it.currentPosition!!
    }
    
    val injector = getInjector() ?: throw SystemError("No Injector")
    val allocations = injector.getInstance(SchedulerService::class.java).fetchSchedulerAllocations().allocationStates
    val verticesConnected2Cycle = lockedVertices.map { DGMaskManager.currentDG.edges[it] ?: listOf() }
        .flatten()
        .filterNot { lockedVertices.contains(it) }
    
    val freeVertex = verticesConnected2Cycle.filterNot { allocations.values.flatten().contains(it) }
        .first()
    
    LOG.debug("Free vertex: {}", freeVertex)
    
    val correspondingVertexOnCycle = lockedVertices.find { DGMaskManager.currentDG.edges[it]!!.contains(freeVertex) }
        ?: error("No corresponding vertex on cycle!")
    
    LOG.debug("Corresponding vertex: {}", correspondingVertexOnCycle)
    
    val vehicle1 = lockedVehicles.find { it.currentPosition == correspondingVertexOnCycle }
        ?: error("No vehicle at corresponding vertex!")
    
    LOG.debug("Vehicle1: {}", vehicle1.name)
    
    val result = mutableMapOf<String, MutableMap<String, List<String>>>()
    var phaseCounter = 1
    // phase1
    result["phase$phaseCounter"] = mutableMapOf()
    result["phase$phaseCounter"]!![vehicle1.name] = listOf(correspondingVertexOnCycle, freeVertex)
    phaseCounter += 1
    // 更新temporaryState
    temporaryState[vehicle1.name] = freeVertex
    LOG.debug("{}", temporaryState)
    LOG.debug("第一阶段规划完成；已将一辆车移动到死锁环之外，当前ppt：{}", result)
    // 找到一辆车，将它移到它的环外的，最近的目标点
    var vertex2Go = ""
    var vehicle2 = ""
    for (v in lockedVehicles) {
        if (v.name == vehicle1.name) continue
        LOG.debug("Vehicle={}, transport order={}", v.name, v.transportOrder)
        val transportOrder = TransportOrderService.getOrder(v.transportOrder!!) // 如果车上没有运单就抛出异常
        val currentDriveOrder = transportOrder.currentDriveOrder
            ?: transportOrder.driveOrders[transportOrder.currentDriveOrderIndex]
        // TODO currentDriveOrderIndex 为 -1 的情况
        // 如果当前的位置已经是当前driveOrder的最后一个位置
        if (v.currentPosition == currentDriveOrder.route!!.finalDestinationPoint) {
            if (currentDriveOrder == transportOrder.driveOrders.last()) {
                error("Already reached the end of the transport order!")
            } else {
                vertex2Go = transportOrder.driveOrders[transportOrder.driveOrders.indexOf(currentDriveOrder) + 1]
                    .route!!.steps
                    .map { it.destinationPoint }
                    .filter { verticesConnected2Cycle.contains(it) } // 距离cycle只有一步
                    .filter {
                        (!allocations.values.flatten().contains(it)) || // 没有被分配，也就是没有被车占领
                            (allocations[v.name]?.contains(it) ?: false) // 或者是被当前车占领
                    }
                    .filterNot { it == freeVertex }
                    .first()
                vehicle2 = v.name
                break
            }
        } else {
            // 下一个点不应定在圈外，可能会跳
            val destinations = currentDriveOrder.route.steps.map { it.destinationPoint }
            val futureVertices = destinations.subList(destinations.indexOf(v.currentPosition) + 1, destinations.size)
            vertex2Go = futureVertices
                .filter { verticesConnected2Cycle.contains(it) }
                .filter {
                    (!allocations.values.flatten().contains(it)) || // 没有被分配，也就是没有被车占领
                        (allocations[v.name]?.contains(it) ?: false) // 或者是被当前车占领
                }
                .filterNot { it == freeVertex }.first()
            vehicle2 = v.name
            break
        }
        
    }
    // 找到vertex2Go对应的环上的点
    // TODO 如果有不止一个点怎么办
    val correspondingVertex = lockedVertices.find { DGMaskManager.currentDG.edges[it]!!.contains(vertex2Go) }
        ?: error("No corresponding vertex.")
    
    val vehicleFilter = { vehicleID: String -> vehicleID == vehicle1.name }
    var tick = 100
    // 旋转，直到要出圈的车到了correspondingVertex
    while (temporaryState[vehicle2] != correspondingVertex) {
        val vehicles2Move = temporaryState.keys.filterNot(vehicleFilter).filterNot { vId ->
            temporaryState.values.contains(deadlock.edges.find { it.fromSpot == temporaryState[vId] }!!.toSpot)
        }
        val currentVertices = vehicles2Move.map { temporaryState[it] }
        val nextVertices = deadlock.edges.filter { currentVertices.contains(it.fromSpot) }.map { it.toSpot }
        // 检查是否冲突
//        val occupiedVertices = temporaryState.keys.filterNot(vehicleFilter).map { temporaryState[it] }
//        if (nextVertices.intersect(occupiedVertices).isNotEmpty()) {
//            error("Conflict occurred, cannot rotate.")
//        }
        vehicles2Move.forEach { vId ->
            val newPosition = deadlock.edges.find { it.fromSpot == temporaryState[vId] }!!.toSpot
            if (result["phase$phaseCounter"] == null) {
                result["phase$phaseCounter"] = mutableMapOf()
            }
            result["phase$phaseCounter"]!![vId] = listOf(temporaryState[vId]!!, newPosition)
            phaseCounter += 1
            temporaryState[vId] = newPosition
        }
        LOG.debug("{}", temporaryState)
        tick -= 1
        if (tick <= 0) break
    }
    // todo 这段代码重复了好几次，提出去
    result["phase$phaseCounter"] = mutableMapOf()
    result["phase$phaseCounter"]!![vehicle2] = listOf(correspondingVertex, vertex2Go)
    phaseCounter += 1
    temporaryState[vehicle2] = vertex2Go
    LOG.debug("{}", temporaryState)
    LOG.debug("第二阶段规划完成；已将死锁环上的一辆车移动到一个处在死锁环之外，且位于其路径上的点，当前ppt：{}", result)
    // 将第一步中移出环的车移回去，注意顺序
    // 除了在环外的两辆车，剩下的车回到死锁位置，vehicle1移回去之后，除了vehicle2之外的所有车前进一步，完成解锁
    /* 不考虑环外的车。
    * 因为之前的所有操作都是在一个单向的环路上进行，而且没有添加车，
    * 所以，除了没有移动过，一直处在死锁位置上的车，如果一辆车到达死锁位置之后，不会挡住后面的车。
    * 换句话说，将车子移动到死锁位置的方式实际上是所有车子全部绕死锁环一圈，那么如果有的车不动，别的车就没法转一整圈，
    * 所以，所有的车都需要移动，所有的车从开始解死锁到这个阶段结束的步数相同，都是环的长度。
    * 因此，移动的策略如下：
    * 对车辆V，它在temporaryState中的位置记为V_t, 它的死锁位置为V_d, 它后面的第i辆车为V_behind[i],
    * 两点之间的距离记为dis(A , B), 从V_t到V_d的距离（边的数量）为dis(V_t , V_d)
    * 满足下列不等式时才不会产生挡路的情况，也就是不需要超车：
    * dis(V_behind[i]_t , V_behind[i]_d) < dis(V_behind[i]_t , V_d); 即后面的车的目标点也在后面
    * 等价于: dis(V_behind[i]_t , V_behind[i]_d) < dis(V_behind[i]_t , V_t) + dis(V_t , V_d)
    * 对于dis(V_t , V_d) = 0, 即车已经在目标点上的情况，在需要时，可以dis(V_t , V_d) = len(Cycle), 也就是让它再转一圈。
    * 每辆车从开始解死锁到此阶段结束，走的距离都是 len(Cycle), 也就是说，“再转一圈”这种操作，每辆车最多只会来一次 */
    
    // 每辆车走了多远
    val traveledDistance: MutableMap<String, Int> = mutableMapOf()
    // traveledDistance == deadlock.edges.size 的时候就是转了一圈，应该已经回到原位并且不会挡其他车的路了
    lockedVehicles.forEach {
        traveledDistance[it.name] = calcDistance(it.currentPosition!!, temporaryState[it.name]!!, deadlock.edges)
    }
    tick = 100
    while (true) {
        val movableVehicles = traveledDistance.keys
            .filter { traveledDistance[it]!! < deadlock.edges.size } // 步数限制
            // 下个点是空点
            .filterNot { vId ->
                temporaryState.values.contains(deadlock.edges.find { it.fromSpot == temporaryState[vId] }!!.toSpot)
            }
        if (movableVehicles.isEmpty()) break
        movableVehicles.forEach { vId ->
            val nextPosition = deadlock.edges.find { it.fromSpot == temporaryState[vId] }!!.toSpot
            if (!result.containsKey("phase$phaseCounter")) {
                result["phase$phaseCounter"] = mutableMapOf()
            }
            result["phase$phaseCounter"]!![vId] = listOf(temporaryState[vId]!!, nextPosition)
            temporaryState[vId] = nextPosition
            traveledDistance[vId] = traveledDistance[vId]!! + 1
        }
        phaseCounter += 1
        LOG.debug("{}", temporaryState)
        tick -= 1
        if (tick <= 0) break
    }
    // 把vehicle1移回去
    if (temporaryState.values.contains(vehicle1.currentPosition)) error("Vehicle1 cannot go back to cycle")
    result["phase$phaseCounter"] = mutableMapOf()
    result["phase$phaseCounter"]!![vehicle1.name] = listOf(temporaryState[vehicle1.name]!!, correspondingVertexOnCycle)
    temporaryState[vehicle1.name] = correspondingVertexOnCycle
    LOG.debug("第三阶段规划完成；已将除第二阶段中移出死锁环的车之外的死锁车辆移回原位，当前ppt：{}", result)
    phaseCounter += 1
    // 除了vehicle2之外的车，都向前走一步
    val vehicles2Move = deadlock.lockedVehicles.map { it.name }.filter { it != vehicle2 }
    val vehiclesMoved = mutableListOf<String>()
    while (vehicles2Move.size != vehiclesMoved.size) {
        val movableVehicles = vehicles2Move.filterNot { vehiclesMoved.contains(it) }.filterNot { vId ->
            temporaryState.values.contains(deadlock.edges.find { it.fromSpot == temporaryState[vId] }!!.toSpot)
        }
        movableVehicles.forEach { vId ->
            val newPosition = deadlock.edges.find { it.fromSpot == temporaryState[vId] }!!.toSpot
            if (result["phase$phaseCounter"] == null) {
                result["phase$phaseCounter"] = mutableMapOf()
            }
            result["phase$phaseCounter"]!![vId] = listOf(temporaryState[vId]!!, newPosition)
            phaseCounter += 1
            temporaryState[vId] = newPosition
        }
        vehiclesMoved.addAll(movableVehicles)
    }
    LOG.debug("{}", temporaryState)
    LOG.debug("第四阶段规划完成；已使除第二阶段移出死锁环的车之外的死锁车辆向前移动一步，当前ppt：{}", result)
    return result
}

fun calcDistance(start: String, end: String, cycle: List<DGEdge>): Int {
    var distance = 0
    var tmp = start
    while (distance < cycle.size && tmp != end) {
        tmp = cycle.find { it.fromSpot == tmp }!!.toSpot
        distance += 1
    }
    if (distance > cycle.size) error("Wrong distance")
    if (distance == cycle.size && start == end) error("One vertex with two names")
    return distance
}
