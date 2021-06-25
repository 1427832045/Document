package org.opentcs.data.order;

/**
 * Defines the various potential states of a drive order.
 */
public enum DriveOrderState {

    /**
     * A drive order's initial state, indicating it being still untouched/not being processed.
     */
    PRISTINE,
    /**
     * Indicates a drive order is part of a transport order.
     *
     * @deprecated Unused. Will be removed.
     */
    @Deprecated
    ACTIVE,
    /**
     * Indicates the vehicle processing the order is currently moving to its destination.
     */
    TRAVELLING,
    /**
     * Indicates the vehicle processing the order is currently executing an operation.
     */
    OPERATING,
    /**
     * Marks a drive order as successfully completed.
     */
    FINISHED,
    /**
     * Marks a drive order as failed.
     */
    FAILED
}
