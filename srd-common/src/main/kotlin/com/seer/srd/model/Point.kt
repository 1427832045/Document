package com.seer.srd.model

data class Point(
    val name: String,
    var properties: Map<String, String>,
    // This point's coordinates in mm.
    val position: Triple = Triple(
        0,
        0,
        0
    ),
    val type: PointType = PointType.HALT_POSITION,
    // The vehicle's (assumed) orientation angle (-360..360) when it is at this  position.
    // May be Double.NaN if an orientation angle is not defined for this point.
    val vehicleOrientationAngle: Double = Double.NaN,
    val incomingPaths: Set<String> = emptySet(),
    val outgoingPaths: Set<String> = emptySet(),
    val attachedLinks: Set<Link> = emptySet(),
    val occupyingVehicle: String? = null
) {

    /**
     * Checks whether parking a vehicle on this point is allowed.
     */
    val isParkingPosition: Boolean
        get() = type == PointType.PARK_POSITION

    /**
     * Checks whether halting on this point is allowed.
     */
    val isHaltingPosition: Boolean
        get() = type == PointType.PARK_POSITION || type == PointType.HALT_POSITION

    override fun toString(): String {
        return "Point(name='$name')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Point

        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

}