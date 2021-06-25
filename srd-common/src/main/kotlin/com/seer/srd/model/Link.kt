package com.seer.srd.model

/**
 * A link connecting a point and a location, expressing that the location is
 * reachable from the point.
 */
data class Link(
    val location: String,
    val point: String,
    val allowedOperations: Set<String> = emptySet()
) {

    fun hasAllowedOperation(operation: String): Boolean {
        return allowedOperations.contains(operation)
    }

    override fun toString(): String {
        return "LocationLink(location='$location', point='$point')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Link

        if (location != other.location) return false
        if (point != other.point) return false

        return true
    }

    override fun hashCode(): Int {
        var result = location.hashCode()
        result = 31 * result + point.hashCode()
        return result
    }

}