package com.seer.srd.route.model

import com.seer.srd.model.*
import org.w3c.dom.Node
import java.util.concurrent.ConcurrentHashMap

class PlantModel(
    val name: String
) {
    val properties: MutableMap<String, String> = ConcurrentHashMap()

    val points: MutableMap<String, Point> = ConcurrentHashMap()
    val locations: MutableMap<String, Location> = ConcurrentHashMap()
    val locationTypes: MutableMap<String, LocationType> = ConcurrentHashMap()
    val paths: MutableMap<String, Path> = ConcurrentHashMap()
    val blocks: MutableMap<String, Block> = ConcurrentHashMap()

    val groups: MutableMap<String, Group> = ConcurrentHashMap()

    val visualLayouts: MutableList<Node> = ArrayList()
}