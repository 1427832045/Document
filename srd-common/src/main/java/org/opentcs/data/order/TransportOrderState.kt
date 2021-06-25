package org.opentcs.data.order

/**
 * This enumeration defines the various states a transport order may be in.
 */
enum class TransportOrderState {
    /**
     * A transport order's initial state.
     * A transport order remains in this state until its parameters have been
     * set up completely.
     */
    RAW,

    /**
     * Set (by a user/client) when a transport order's parameters have been set
     * up completely and the kernel should dispatch it when possible.
     */
    ACTIVE,

    /**
     * Marks a transport order as ready to be dispatched to a vehicle (i.e. all
     * its dependencies have been finished).
     */
    DISPATCHABLE,

    /**
     * Marks a transport order as being processed by a vehicle.
     */
    BEING_PROCESSED,

    /**
     * Indicates the transport order is withdrawn from a processing vehicle but
     * not yet in its final state (which will be FAILED), as the vehicle has not
     * yet finished/cleaned up.
     */
    WITHDRAWN,

    /**
     * Marks a transport order as successfully completed.
     */
    FINISHED,

    /**
     * General failure state that marks a transport order as failed.
     */
    FAILED,

    /**
     * Failure state that marks a transport order as unroutable, i.e. it is
     * impossible to find a route that would allow a vehicle to process the
     * transport order completely.
     */
    UNROUTABLE;

    /**
     * Checks if this state is a final state for a transport order.
     *
     * @return `true` if, and only if, this state is a final state
     * for a transport order - i.e. FINISHED, FAILED or UNROUTABLE.
     */
    val isFinalState: Boolean
        get() = this == FINISHED || this == FAILED || this == UNROUTABLE
}