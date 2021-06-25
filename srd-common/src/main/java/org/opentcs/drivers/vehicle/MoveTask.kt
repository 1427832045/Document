package org.opentcs.drivers.vehicle

import com.fasterxml.jackson.databind.JsonNode
import com.seer.srd.route.VehicleSimulation
import com.seer.srd.util.mapper
import com.seer.srd.route.routeConfig
import java.lang.Exception

val isRouteSimulationMode = routeConfig.vehicleSimulation != VehicleSimulation.None

fun movementCommand2JsonNode(cmd: MovementCommand): JsonNode {
    val node = mapper.createObjectNode()
    node.put("id", if(cmd.step.sourcePoint != null) cmd.step.destinationPoint else "SELF_POSITION")
    node.put("source_id", cmd.step.sourcePoint ?: "SELF_POSITION")
    node.put("task_id", cmd.id)
    node.put("operation", cmd.operation)
//    node["final_movement"] = cmd.isFinalMovement
    // properties
    cmd.properties.forEach {
        when {
            arrayOf("script_args", "args").contains(it.key) -> {
                node.set<JsonNode>(it.key, mapper.readTree(it.value))
            }
            it.key.startsWith("robot:") -> {
                if ("robot:switchMap" == it.key) {
                    node.put("script_name", "syspy/switchMap.py")
                    node.set<JsonNode>("script_args", mapper.createObjectNode().put("map", it.value)
                                                                               .put("switchPoint", cmd.step.sourcePoint))
                    node.put("operation", "Script")
                    node.put("script_stage", 0)
                }
            }
            it.key.startsWith("device:") -> {
                // do nothing
            }
            it.key.startsWith("sim:") -> {
                if (isRouteSimulationMode) {
                    node.put(it.key, it.value)
                }
            }
            else -> {
                when {
                    isInt(it.value) -> node.put(it.key, it.value.toInt())
                    isDouble(it.value) -> node.put(it.key, it.value.toDouble())
                    isBoolean(it.value) -> node.put(it.key, toBoolean(it.value))
                    else -> node.put(it.key, it.value)
                }
            }
        }
    }
    return node
}

fun isInt(s: String): Boolean {
    return try {
        s.toInt()
        true
    } catch (ex: Exception) {
        false
    }
}

fun isDouble(s: String): Boolean {
    return try {
        s.toDouble()
        true
    } catch (ex: Exception) {
        false
    }
}

fun isBoolean(s: String): Boolean {
    return when(s.toLowerCase()) {
        "true" -> true
        "false" -> true
        else -> false
    }
}

fun toBoolean(s: String): Boolean {
    return when(s.toLowerCase()) {
        "true" -> true
        else -> false
    }
}