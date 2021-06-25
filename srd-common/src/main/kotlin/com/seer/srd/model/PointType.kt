package com.seer.srd.model

/**
 * Describes the types of positions in a driving course.
 */
enum class PointType {
    /**
     * Indicates a position at which a vehicle may halt temporarily, e.g. for executing an
     * operation.
     * The vehicle is also expected to report in when it arrives at such a position.
     * It may not park here for longer than necessary, though.
     */
    HALT_POSITION,

    /**
     * Indicates a position at which a vehicle may halt for longer periods of time when it is not
     * processing orders.
     * The vehicle is also expected to report in when it arrives at such a position.
     */
    PARK_POSITION
}