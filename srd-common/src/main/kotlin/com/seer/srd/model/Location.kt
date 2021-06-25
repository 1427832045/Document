package com.seer.srd.model

/**
 * A location at which a Vehicle may perform an action.
 *
 * A location must be linked to at least one [Point] to be reachable for a vehicle.
 * It may be linked to multiple points.
 * As long as a link's specific set of allowed operations is empty (which is the default), all
 * operations defined by the location's referenced [LocationType] are allowed at the linked
 * point.
 * If the link's set of allowed operations is not empty, only the operations contained in it are
 * allowed at the linked point.
 */
data class Location(
    val name: String,
    var properties: Map<String, String>,
    val type: String,
    val position: Triple,
    val attachedLinks: Set<Link>
) {

    override fun toString(): String {
        return "Location(name='$name')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Location

        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

}
