package com.seer.srd.vehicle

import com.seer.srd.model.Triple
import org.opentcs.data.order.OrderConstants
import org.opentcs.drivers.vehicle.LoadHandlingDevice

data class Vehicle(
    val name: String,
    val properties: Map<String, String>,
    // This vehicle's length (in mm).
    val length: Int = 1,
    val energyLevelGood: Int = 100,
    val energyLevelCritical: Int = 100,
    val energyLevelFullyRecharged: Int = 100,
    val energyLevelSufficientlyRecharged: Int = 100,
    val energyLevel: Int = 100,
    val maxVelocity: Int,
    val maxReverseVelocity: Int,
    val rechargeOperation: String,
    // The current (state of the) load handling devices of this vehicle.
    val loadHandlingDevices: List<LoadHandlingDevice>,
    val state: State,
    val procState: ProcState,
    val integrationLevel: IntegrationLevel,
    val transportOrder: String?,
    val orderSequence: String?,
    val processableCategories: Set<String> = setOf(OrderConstants.CATEGORY_ANY),
    val routeProgressIndex: Int,
    val currentPosition: String?,
    val nextPosition: String?,
    val precisePosition: Triple?,
    val orientationAngle: Double,
    val paused: Boolean = false,
    val owner: String = "",
    val isDominating: Boolean = false,
    val allocations: Set<String> = emptySet(),
    val lastTerminateTimeMs: Long = 0L,
    val adapterEnabled: Boolean = true,
    // -1 means cannot read relocStatus from RBK
    var relocStatus: Int = -1
) {

    /**
     * Checks whether the vehicle's energy level is critical.
     */
    fun isEnergyLevelCritical(): Boolean {
        return energyLevel <= energyLevelCritical
    }

    /**
     * Checks whether the vehicle's energy level is degraded (not *good* any more).
     */
    val isEnergyLevelDegraded: Boolean
        get() = energyLevel <= energyLevelGood

    /**
     * Checks whether the vehicle's energy level is fully recharged.
     */
    fun isEnergyLevelFullyRecharged(): Boolean {
        return energyLevel >= energyLevelFullyRecharged
    }

    /**
     * Checks whether the vehicle's energy level is sufficiently recharged.
     */
    fun isEnergyLevelSufficientlyRecharged(): Boolean {
        return energyLevel >= energyLevelSufficientlyRecharged
    }

    fun hasProcState(procState: ProcState): Boolean {
        return procState == this.procState
    }

    fun hasState(state: State): Boolean {
        return state == this.state
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Vehicle

        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

    /**
     * Checks if this vehicle is currently processing any transport order.
     */
    val isProcessingOrder: Boolean
        get() = transportOrder != null

    /**
     * The elements of this enumeration describe the various possible states of a vehicle.
     */
    enum class State {
        /**
         * The vehicle's current state is unknown, e.g. because communication with
         * it is currently not possible for some reason.
         */
        UNKNOWN,

        /**
         * The vehicle's state is known and it's not in an error state, but it is
         * not available for receiving orders.
         */
        UNAVAILABLE,

        /**
         * There is a problem with the vehicle.
         */
        ERROR,

        /**
         * The vehicle is currently idle/available for processing movement orders.
         */
        IDLE,

        /**
         * The vehicle is processing a movement order.
         */
        EXECUTING,

        /**
         * The vehicle is currently recharging its battery/refilling fuel.
         */
        CHARGING
    }

    /**
     * A vehicle's state of integration into the system.
     */
    enum class IntegrationLevel {
        /**
         * The vehicle's reported position is ignored.
         */
        TO_BE_IGNORED,

        /**
         * The vehicle's reported position is noticed, meaning that resources will not be reserved for
         * it.
         */
        TO_BE_NOTICED,

        /**
         * The vehicle's reported position is respected, meaning that resources will be reserved for it.
         */
        TO_BE_RESPECTED,

        /**
         * The vehicle is fully integrated and may be assigned to transport orders.
         */
        TO_BE_UTILIZED
    }

    /**
     * A vehicle's processing state as seen by the dispatcher.
     */
    enum class ProcState {
        /**
         * The vehicle is currently unavailable for order processing and cannot be
         * dispatched. This is a vehicle's initial state.
         */
        UNAVAILABLE,

        /**
         * The vehicle is currently not processing a transport order.
         */
        IDLE,

        /**
         * The vehicle is currently processing a transport order and is waiting for
         * the next drive order to be assigned to it.
         */
        AWAITING_ORDER,

        /**
         * The vehicle is currently processing a drive order.
         */
        PROCESSING_ORDER
    }

    /**
     * The elements of this enumeration represent the possible orientations of a vehicle.
     */
    enum class Orientation {
        /**
         * Indicates that the vehicle is driving/standing oriented towards its
         * front.
         */
        FORWARD,

        /**
         * Indicates that the vehicle is driving/standing oriented towards its
         * back.
         */
        BACKWARD,

        /**
         * Indicates that the vehicle's orientation is undefined/unknown.
         */
        UNDEFINED
    }

    companion object {
        /**
         * A value indicating that no route steps have been travelled for the current drive order, yet.
         */
        const val ROUTE_INDEX_DEFAULT = -1
    }
}