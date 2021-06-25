package org.opentcs.data.order

/**
 * Describes the destination of a drive order.
 */
class Destination(
    val location: String,
    /**
     * The actual destination (point or location).
     */
    val destination: String,
    val operation: String = OP_NOP,
    val properties: Map<String, String> = emptyMap()
) {

    companion object {
        /**
         * An operation constant for doing nothing.
         */
        const val OP_NOP = "NOP"

        /**
         * An operation constant for parking the vehicle.
         */
        const val OP_PARK = "PARK"

        /**
         * An operation constant for sending the vehicle to a point without a location associated to it.
         */
        const val OP_MOVE = "MOVE"
    }

    override fun toString(): String {
        return "Destination(location='$location', destination='$destination', operation='$operation'"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Destination

        if (location != other.location) return false
        if (destination != other.destination) return false
        if (operation != other.operation) return false

        return true
    }

    override fun hashCode(): Int {
        var result = location.hashCode()
        result = 31 * result + destination.hashCode()
        result = 31 * result + operation.hashCode()
        return result
    }

}