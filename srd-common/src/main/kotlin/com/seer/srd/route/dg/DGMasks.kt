package com.seer.srd.route.dg

import com.seer.srd.route.deadlock.DeadlockType
import com.seer.srd.route.deadlock.canInsertPointInDeadlockOnEdge
import com.seer.srd.route.domain.DGEdge
import com.seer.srd.route.domain.DGVertex
import com.seer.srd.route.domain.Deadlock
import com.seer.srd.route.domain.SimpleDG
import com.seer.srd.route.model.PlantModel
import com.seer.srd.route.service.VehicleService
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("com.seer.srd.route.dg.DGMasks")

fun trivialMask(dg: SimpleDG) = dg

fun getMiddleVertexName(e: DGEdge) = "${e.fromSpot}-${e.toSpot}-temp-spot"

fun insertPointsInTheMiddleMaskFactory(deadlock: Deadlock): DGMask = { dg: SimpleDG ->
    val mutableVertices = dg.vertices.toMutableMap()
    val mutableEdges = dg.edges.toMutableMap()
    
    for (e in deadlock.edges) {
        if (!canInsertPointInDeadlockOnEdge(e)) continue
        
        val fromSpot = DGMaskManager.currentDG.vertices[e.fromSpot] ?: error("Wrong edge")
        val toSpot = DGMaskManager.currentDG.vertices[e.toSpot] ?: error("Wrong edge")
        
        val newVertex = DGVertex(
            id = getMiddleVertexName(e),
            x = (fromSpot.x + toSpot.x) / 2,
            y = (fromSpot.y + toSpot.y) / 2
        )
        
        LOG.debug("adding point: {}", newVertex)
        mutableVertices[newVertex.id] = newVertex
        
        if (mutableEdges[e.fromSpot]?.contains(newVertex.id)!!) LOG.error("杨达，你代码写的有问题")
        if (mutableEdges.keys.contains(newVertex.id)) LOG.error("杨达，你代码写的有问题")
        
        mutableEdges[e.fromSpot] = mutableEdges[e.fromSpot]!! + newVertex.id
        mutableEdges[newVertex.id] = listOf(e.toSpot)
        
        // TODO 有没有更好的写法
        mutableEdges[e.fromSpot] = mutableEdges[e.fromSpot]!!.subList(1, mutableEdges[e.fromSpot]!!.size)
    }
    
    SimpleDG(
        name = dg.name + "-with-temp-points",
        edges = mutableEdges,
        vertices = mutableVertices
    )
}

fun ignoreOccupiedPointsMaskFactory(deadlock: Deadlock) = { dg: SimpleDG ->
    val occupiedPoints = VehicleService.listVehicles()
        .filter { !deadlock.lockedVehicles.map { v -> v.name }.contains(it.name) }.map { it.currentPosition }
    LOG.debug("Occupied points: {}", occupiedPoints)
    val unoccupiedVertices = dg.vertices.filter { !occupiedPoints.contains(it.key) }
    val unoccupiedEdges = dg.edges.filter { unoccupiedVertices.containsKey(it.key) }.toMutableMap()
    for (start in unoccupiedEdges.keys) {
        unoccupiedEdges[start] = unoccupiedEdges[start]!!.filter { unoccupiedVertices.contains(it) }
    }
    SimpleDG(
        name = dg.name + "without-occupied-points",
        vertices = unoccupiedVertices,
        edges = unoccupiedEdges
    )
}

fun plantModelMaskFactory(plantModel: PlantModel): DGMask {
    return { _: SimpleDG ->
        val vertices = mutableMapOf<String, DGVertex>()
        val edges = mutableMapOf<String, List<String>>()
        
        for (point in plantModel.points.values) {
            vertices[point.name] = DGVertex(point.name, point.position.x, point.position.y)
        }
        for (path in plantModel.paths.values) {
            if (edges.containsKey(path.sourcePoint)) {
                edges[path.sourcePoint] = edges[path.sourcePoint]!!.plus(path.destinationPoint)
            } else {
                edges[path.sourcePoint] = listOf(path.destinationPoint)
            }
        }
        
        SimpleDG(name = "${plantModel.name}-dg", vertices = vertices, edges = edges)
    }
}

fun getMask4Deadlock(deadlockType: DeadlockType, deadlock: Deadlock) =
    when (deadlockType) {
        DeadlockType.Exchange -> listOf(ignoreOccupiedPointsMaskFactory(deadlock))
        DeadlockType.Cycle -> listOf(
            ignoreOccupiedPointsMaskFactory(deadlock)
//            insertPointsInTheMiddleMaskFactory(deadlock)
        )
        DeadlockType.Other -> listOf(::trivialMask)
    }


fun pathLockMaskFactory(paths2Lock: Set<DGEdge>) = { dg: SimpleDG ->
    val mutableEdges = dg.edges.toMutableMap()
    
    for (edge in paths2Lock) {
        if (mutableEdges[edge.fromSpot] == null || mutableEdges[edge.fromSpot]!!.contains(edge.toSpot)) continue
        // TODO 找不到要删除的边应该报错还是输出日志？
        mutableEdges[edge.fromSpot]!!.minus(edge.toSpot)
    }
    
    SimpleDG(
        name = dg.name + "-with-paths-locked",
        edges = mutableEdges,
        vertices = dg.vertices
    )
}