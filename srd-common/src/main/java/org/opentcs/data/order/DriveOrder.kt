package org.opentcs.data.order

/**
 * Describes a sequence of movements and an optional operation at the end that a Vehicle is
 * supposed to execute.
 */
data class DriveOrder(
    val destination: Destination,
    val transportOrder: String? = null,
    val route: Route? = null,
    val state: DriveOrderState = DriveOrderState.PRISTINE
) {

    override fun toString(): String {
        return "DriveOrder(destination=$destination, transportOrder=$transportOrder, route=$route, state=$state)"
    }

}