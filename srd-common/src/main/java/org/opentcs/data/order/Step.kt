package org.opentcs.data.order

import com.seer.srd.vehicle.Vehicle

/**
 * A single step in a route.
 */
data class Step(
    val path: String?, // The path to travel.
    val sourcePoint: String?, // The point that the vehicle is starting from.
    val destinationPoint: String,
    val vehicleOrientation: Vehicle.Orientation,
    val routeIndex: Int, // This step's index in the vehicle's route.
    val executionAllowed: Boolean = true // Whether execution of this step is allowed.
) {
    
    override fun toString(): String {
        return destinationPoint
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as Step
        
        if (path != other.path) return false
        if (sourcePoint != other.sourcePoint) return false
        if (destinationPoint != other.destinationPoint) return false
        if (vehicleOrientation != other.vehicleOrientation) return false
        if (routeIndex != other.routeIndex) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = path?.hashCode() ?: 0
        result = 31 * result + (sourcePoint?.hashCode() ?: 0)
        result = 31 * result + destinationPoint.hashCode()
        result = 31 * result + routeIndex
        return result
    }
}