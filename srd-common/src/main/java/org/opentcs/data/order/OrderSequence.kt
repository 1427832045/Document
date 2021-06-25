package org.opentcs.data.order

import org.bson.codecs.pojo.annotations.BsonId
import java.time.Instant

/**
 * Describes a process spanning multiple [TransportOrder]s which are to be executed
 * subsequently by the same Vehicle.
 *
 * The most important rules for order sequence processing are:
 *
 *  * Only transport orders that have not yet been activated may be added to an order sequence.
 * Allowing them to be added at a later point of time would imply that, due to concurrency in the
 * kernel, a transport order might happen to be dispatched at the same time or shortly after it is
 * added to a sequence, regardless of if its predecessors in the sequence have already been finished
 * or not.
 *  * The *intendedVehicle* of a transport order being added to an order sequence must be
 * the same as that of the sequence itself.
 * If it is `null` in the sequence, a vehicle that will process all orders in the
 * sequence will be chosen automatically once the first order in the sequence is dispatched.
 *  * If an order sequence is marked as *complete* and all transport orders belonging to it
 * have arrived in state `FINISHED` or `FAILED`, it will be marked as
 * *finished* implicitly.
 *  * If a transport order belonging to an order sequence fails and the sequence's
 * *failureFatal* flag is set, all subsequent orders in the sequence will automatically be
 * considered (and marked as) failed, too, and the order sequence will implicitly be marked as
 * *complete* (and *finished*).
 *
 */
data class OrderSequence(
    @BsonId val name: String,
    val createdOn: Instant = Instant.now(),
    val properties: Map<String, String> = emptyMap(),
    val category: String = OrderConstants.CATEGORY_NONE,
    val orders: List<String> = emptyList(),
    /**
     * The index of the order that was last finished in the sequence.
     * -1 if none was finished, yet.
     */
    val finishedIndex: Int = -1,
    /**
     * Indicates whether this order sequence is complete and will not be extended by more orders.
     */
    val complete: Boolean = false,
    /**
     * Indicates whether this order sequence has been processed completely.
     */
    val finished: Boolean = false,
    /**
     * Indicates whether the failure of one order in this sequence is fatal to all subsequent orders.
     */
    val failureFatal: Boolean = false,
    val intendedVehicle: String? = null,
    val processingVehicle: String? = null
) {

    val nextUnfinishedOrder: String?
        get() = when {
            finished -> null
            finishedIndex + 1 >= orders.size -> null
            else -> orders[finishedIndex + 1]
        }

    override fun toString(): String {
        return "OrderSequence(name='$name')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as OrderSequence

        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

}