package org.opentcs.data.order

import org.bson.codecs.pojo.annotations.BsonId
import java.time.Instant
import java.util.*

data class TransportOrder(
    @BsonId val name: String,
    val properties: Map<String, String> = emptyMap(),
    val category: String = OrderConstants.CATEGORY_NONE,
    // A set of TransportOrders that must have been finished before this one may
    val dependencies: Set<String> = emptySet(),
    // A list of rejections for this transport order.
    val rejections: List<Rejection> = emptyList(),
    // The drive orders this transport order consists of.
    val driveOrders: List<DriveOrder> = emptyList(),
    // The index of the currently processed drive order.
    val currentDriveOrderIndex: Int = -1,
    // This transport order's current state.
    val state: TransportOrderState = TransportOrderState.RAW,
    // The point of time at which this transport order was created.
    val creationTime: Instant = Instant.now(),
    // The point of time at which processing of this transport order must be finished.
    val deadline: Instant = Instant.ofEpochMilli(Long.MAX_VALUE),
    // The point of time at which processing of this transport order was finished.
    val finishedTime: Instant? = null,
    val intendedVehicle: String? = null,
    val processingVehicle: String? = null,
    val wrappingSequence: String? = null,
    val isDispensable: Boolean = false
) {
    
    fun hasState(otherState: TransportOrderState): Boolean {
        return state == otherState
    }
    
    /**
     * Returns a list of DriveOrders that have been processed already.
     */
    val pastDriveOrders: List<DriveOrder>
        get() {
            val result: MutableList<DriveOrder> = ArrayList()
            for (i in 0 until currentDriveOrderIndex) {
                result.add(driveOrders[i])
            }
            return result
        }
    
    //@get:Nonnull
    //@set:Throws(IllegalArgumentException::class)
    //@set:Deprecated("Will become immutable.")
    val futureDriveOrders: List<DriveOrder>
        get() {
            val result: MutableList<DriveOrder> = ArrayList()
            for (i in currentDriveOrderIndex + 1 until driveOrders.size) {
                result.add(driveOrders[i])
            }
            return result
        }
    //set(newOrders) {
    //    Objects.requireNonNull(newOrders, "newOrders")
    //    Assertions.checkState(
    //        currentDriveOrderIndex < 0,
    //        "Already processing drive order with index %s",
    //        currentDriveOrderIndex
    //    )
    //    val orderCount = newOrders.size
    //    Assertions.checkArgument(
    //        orderCount == driveOrders.size,
    //        "newOrders has wrong size: %s, should be %s",
    //        orderCount,
    //        driveOrders.size
    //    )
    //    // Check if the destinations of the given drive orders are equivalent to the
    //    // ones we have.
    //    for (i in 0 until orderCount) {
    //        val myOrder = driveOrders[i]
    //        val newOrder = newOrders[i]
    //        Assertions.checkArgument(
    //            myOrder.destination == newOrder.destination,
    //            "newOrders' destinations do not equal mine"
    //        )
    //    }
    //    // Copy the given drive orders' data to ours.
    //    for (i in 0 until orderCount) {
    //        val newOrder = newOrders[i]
    //        driveOrders[i] = driveOrders[i]
    //            .withRoute(newOrder.route)
    //            .withState(newOrder.state)
    //    }
    //}
    
    val currentDriveOrder: DriveOrder?
        get() = if (currentDriveOrderIndex >= 0 && currentDriveOrderIndex < driveOrders.size)
            driveOrders[currentDriveOrderIndex] else null
    
    //@Deprecated("Will become immutable.")
    //@Throws(IllegalStateException::class)
    //fun setInitialDriveOrder() {
    //    Assertions.checkState(currentDriveOrderIndex < 0, "currentDriveOrder already set")
    //    Assertions.checkState(!driveOrders.isEmpty(), "driveOrders is empty")
    //    currentDriveOrderIndex = 0
    //}
    
    
    //@Deprecated("Will become immutable.")
    //fun setNextDriveOrder() {
    //    currentDriveOrderIndex++
    //}
    
    fun withCurrentDriveOrderState(newState: DriveOrderState): TransportOrder? {
        if (currentDriveOrderIndex < 0 || currentDriveOrderIndex >= driveOrders.size) return null
        val driveOrders = ArrayList(driveOrders)
        driveOrders[currentDriveOrderIndex] = driveOrders[currentDriveOrderIndex].copy(state = newState)
        return copy(driveOrders = driveOrders)
    }
    
    override fun toString(): String {
        return "TransportOrder(name='$name')"
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as TransportOrder
        
        if (name != other.name) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        return name.hashCode()
    }
    
    companion object {
        
        fun newWithDriverOrders(name: String, driveOrders: List<DriveOrder>): TransportOrder {
            return TransportOrder(name, driveOrders = driveOrders)
        }
        
    }
}