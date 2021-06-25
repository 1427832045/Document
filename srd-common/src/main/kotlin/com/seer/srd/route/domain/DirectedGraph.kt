package com.seer.srd.route.domain

data class DGVertex(
    val id: String,
    // val name: String = id,
    val x: Long,
    val y: Long
)

data class DGEdge(val fromSpot: String, val toSpot: String)

data class SimpleDG(
    val name: String = "DefaultEmptyDG",
    val vertices: Map<String, DGVertex> = emptyMap(), // TODO 要不要用mutable map?
    val edges: Map<String, List<String>> = emptyMap() // TODO 这里用Set更符合实际意义
)