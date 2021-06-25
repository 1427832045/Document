package com.seer.srd.model

data class Path(
    val name: String,
    var properties: Map<String, String>,
    val sourcePoint: String,
    val destinationPoint: String,
    // The length of this path (in mm).
    val length: Long = 1,
    // An explicit (unitless) weight that can be used to influence routing.
    // The higher the value, the more travelling this path costs.
    val routingCost: Long = 1,
    val maxVelocity: Int = 1000,
    var maxReverseVelocity: Int = 1000,
    val isLocked: Boolean = false
) {

    /**
     * Checks whether this path is navigable in forward direction.
     */
    val isNavigableForward: Boolean
        get() = !isLocked && maxVelocity != 0

    /**
     * Checks whether this path is navigable in backward/reverse direction.
     */
    val isNavigableReverse: Boolean
        get() = !isLocked && maxReverseVelocity != 0

    override fun toString(): String {
        return "Path(name='$name')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Path

        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

}