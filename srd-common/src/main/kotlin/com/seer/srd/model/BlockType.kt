package com.seer.srd.model

/**
 * Describes the types of blocks in a driving course.
 */
enum class BlockType {
    /**
     * The resources aggregated in this block can only be used by one vehicle at the same time.
     */
    SINGLE_VEHICLE_ONLY,

    /**
     * The resources aggregated in this block can be used by multiple vehicles, but only if they
     * enter the block in the same direction.
     */
    SAME_DIRECTION_ONLY
}
