package org.opentcs.data.order

import org.opentcs.util.Assertions

/**
 * A route for a Vehicle, consisting of a sequence of steps (pairs of Paths and Points)
 * that need to be processed in their given order.
 */
data class Route(
    val steps: List<Step>,
    val costs: Long
) {
    /**
     * Returns the final destination point that is reached by travelling this route.
     * (I.e. returns the destination point of this route's last step.)
     */
    val finalDestinationPoint: String = steps[steps.size - 1].destinationPoint
    
    override fun toString(): String {
        return "Route{steps=$steps, costs=$costs}"
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as Route
        
        if (steps != other.steps) return false
        if (costs != other.costs) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = steps.hashCode()
        result = 31 * result + costs.hashCode()
        return result
    }
    
    init {
        Assertions.checkArgument(steps.isNotEmpty(), "routeSteps may not be empty")
    }
}