package com.seer.srd.route.dg

import com.seer.srd.route.domain.SimpleDG
import org.slf4j.LoggerFactory
import java.util.*

private val LOG = LoggerFactory.getLogger("com.seer.srd.route.dg.Operations")

/// 耳朵分解
// 使用bibox需要将有向图的子图转换为无向图，解死锁时可能会违反平时的行驶规则
// 返回的类型还是有向图的类型，但是事实上是一个无向图
fun convert2UndirectedGraph(dg: SimpleDG): SimpleDG {
    val edges = dg.edges.toMutableMap()
    for ((vertex, to_vertices) in edges) {
        for (to_vertex in to_vertices) {
            edges[to_vertex] = (edges[to_vertex]!!.toSet() + vertex).toList()
        }
    }
    return SimpleDG(name = dg.name + "-undirected", vertices = dg.vertices, edges = edges)
}

data class DFSTreeNode(
    val id: String,
    val parent_id: String,
    val backEdges: Set<String>,
    val timestamp: Int // 发现时间
)

typealias DFSTree = Map<String, DFSTreeNode>

fun depthFirstTraverse(dg: SimpleDG, start: String): DFSTree {
    val result = mutableMapOf<String, DFSTreeNode>()
    var tempNode = dg.vertices[start] ?: error("No start vertex in graph")
    var lastNode = tempNode
    
    val visited = mutableMapOf<String, Boolean>()
    for (id in dg.vertices.keys) {
        visited[id] = false
    }
    var timestamp = 0
    while (!visited.values.all { it }) {
        LOG.debug("Visiting vertex: {}, current time: [}", tempNode.id, timestamp)
        if (visited[tempNode.id] == false) {
            if (tempNode.id == start) {
                val dfsTreeRoot = DFSTreeNode(tempNode.id, tempNode.id, setOf(), timestamp)
                result[dfsTreeRoot.id] = dfsTreeRoot
            } else {
                val backEdges = dg.edges[tempNode.id]!!.filter { result.containsKey(it) }.toSet()
                val dfsTreeNode = DFSTreeNode(tempNode.id, lastNode.id, backEdges, timestamp)
                result[dfsTreeNode.id] = dfsTreeNode
            }
            timestamp += 1
        }
        visited[tempNode.id] = true
        var canForward = false
        for (id in dg.edges[tempNode.id]!!) {
            if (visited[id] == false) {
                lastNode = tempNode
                tempNode = dg.vertices[id]!!
                canForward = true
                break
            }
        }
        if (!canForward) {
            LOG.debug("Going back")
            tempNode = lastNode
            lastNode = dg.vertices[result[lastNode.id]!!.parent_id]!!
        }
    }
    return result
}

data class Ear(
    val path: List<String>,
    val isCycle: Boolean
)

fun earDecomposition(dfsTree: DFSTree): List<Ear> {
    val visited = mutableMapOf<String, Boolean>()
    for (id in dfsTree.keys) {
        visited[id] = false
    }
    val result = mutableListOf<Ear>()
    
    for (id in dfsTree.keys) {
        val backEdges = dfsTree[id]!!.backEdges
        
        if (backEdges.isNotEmpty()) {
            for (startId in backEdges) {
                var tempId = dfsTree[id]!!.parent_id
                val path = mutableListOf(startId, id)
                while (!(visited[tempId] == true || tempId == startId)) {
                    path.add(tempId)
                    visited[tempId] = true
                    tempId = dfsTree[tempId]!!.parent_id
                }
                var isCycle = false
                if (tempId == startId) {
                    isCycle = true
                }
                result.add(Ear(path, isCycle))
            }
        }
    }
    return result
}
/// end 耳朵分解

/// 最短路搜索
// 寻找两个点之间的所有路径，根据长度升序排列
// https://stackoverflow.com/questions/9535819/find-all-paths-between-two-graph-nodes
// https://www.jdoodle.com/a/ukx
data class Step(val id: String, val parent: Step?, val cost: Int) : Comparable<Step> {
    fun seen(node: String): Boolean {
        return if (this.id == node) {
            true
        } else if (parent == null) {
            false
        } else {
            this.parent.seen(node)
        }
    }
    
    fun generatePath(): List<String> {
        val path: MutableList<String> = if (this.parent != null) {
            this.parent.generatePath().toMutableList()
        } else {
            mutableListOf()
        }
        path.add(this.id)
        return path
    }
    
    override fun compareTo(other: Step): Int {
        if (this.cost != other.cost) {
            return this.cost.compareTo(other.cost)
        }
        if (this.id != other.id) {
            return this.id.compareTo(other.id)
        }
        if (this.parent != null) {
            if (other.parent == null) return 1
            return this.parent.compareTo(other.parent)
        }
        if (other.parent == null) {
            return 0
        }
        return -1
    }
}

class PathGenerator(private val g: SimpleDG,private val start: String, private val goal: String) {
    private val pending: NavigableSet<Step>
    
    init {
        if (!this.g.vertices.containsKey(this.start)) {
            error("No start vertex in graph")
        }
        if (!this.g.vertices.containsKey(this.goal)) {
            error("No goal vertex in graph")
        }
        this.pending = TreeSet()
    }
    
    fun nextShortestPath(): List<String> {
        var current: Step? = this.pending.pollFirst()
        while (current != null) {
            if (current.id == this.goal) {
                return current.generatePath()
            }
            for (neighbor in this.g.edges[current.id]!!) {
                if (!current.seen(neighbor)) {
                    val nextStep = Step(neighbor, current, current.cost + 1) // dijkstra
                    this.pending.add(nextStep)
                }
            }
            current = this.pending.pollFirst()
        }
        return listOf()
    }
}
/// end 最短路搜索