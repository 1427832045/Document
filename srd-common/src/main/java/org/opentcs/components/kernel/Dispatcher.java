package org.opentcs.components.kernel;

import javax.annotation.Nonnull;

import org.opentcs.components.Lifecycle;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.data.order.TransportOrder;

/**
 * This interface declares the methods a dispatcher module for the openTCS
 * kernel must implement.
 * <p>
 * A dispatcher manages the distribution of transport orders among the vehicles
 * in a system. It is basically event-driven, where an event can be a new
 * transport order being introduced into the system or a vehicle becoming
 * available for processing existing orders.
 * </p>
 */
public interface Dispatcher extends Lifecycle {

    /**
     * The key of a parking position property defining it's priority.
     * <p>
     * Whether and in what way this is respected for assigning a parking position to a vehicle is
     * implementation-specific.
     * </p>
     */
    String PROPKEY_PARKING_POSITION_PRIORITY = "robot:parkingPositionPriority";
    /**
     * The key of a vehicle property defining the name of the vehicle's assigned parking position.
     * <p>
     * Whether and in what way this is respected for selecting a parking position is
     * implementation-specific.
     * </p>
     */
    String PROPKEY_ASSIGNED_PARKING_POSITION = "robot:assignedParkingPosition";
    /**
     * The key of a vehicle property defining the name of the vehicle's preferred parking position.
     * <p>
     * Whether and in what way this is respected for selecting a parking position is
     * implementation-specific.
     * </p>
     */
    String PROPKEY_PREFERRED_PARKING_POSITION = "robot:preferredParkingPosition";
    /**
     * The key of a vehicle property defining the name of the vehicle's assigned recharge location.
     * <p>
     * Whether and in what way this is respected for selecting a recharge location is
     * implementation-specific.
     * </p>
     */
    String PROPKEY_ASSIGNED_RECHARGE_LOCATION = "robot:assignedRechargeLocation";
    /**
     * The key of a vehicle property defining the name of the vehicle's preferred recharge location.
     * <p>
     * Whether and in what way this is respected for selecting a recharge location is
     * implementation-specific.
     * </p>
     */
    String PROPKEY_PREFERRED_RECHARGE_LOCATION = "robot:preferredRechargeLocation";

    /**
     * Notifies the dispatcher that it should start the dispatching process.
     */
    void dispatch();

    /**
     * Notifies the dispatcher that the given transport order is to be withdrawn/aborted.
     *
     * @param order          The transport order to be withdrawn/aborted.
     * @param immediateAbort Whether the order should be aborted immediately instead of withdrawn.
     */
    default void withdrawOrder(@Nonnull TransportOrder order, boolean immediateAbort) {
        withdrawOrder(order, immediateAbort, false);
    }

    /**
     * Notifies the dispatcher that the given transport order is to be
     * withdrawn/aborted and any vehicle that might be processing it to be
     * stopped.
     *
     * @param order          The transport order to be withdrawn/aborted.
     * @param immediateAbort Whether the order should be aborted immediately
     *                       instead of withdrawn.
     * @param disableVehicle Whether to set the processing vehicle's processing
     *                       state to UNAVAILABLE after withdrawing the order to prevent the vehicle
     *                       being dispatched again.
     */
    void withdrawOrder(@Nonnull TransportOrder order, boolean immediateAbort, boolean disableVehicle);

    /**
     * Notifies the dispatcher that any order a given vehicle might be processing is to be withdrawn.
     *
     * @param vehicle        The vehicle whose order is withdrawn.
     * @param immediateAbort Whether the vehicle's order should be aborted immediately instead of
     *                       withdrawn.
     */
    default void withdrawOrder(@Nonnull Vehicle vehicle, boolean immediateAbort) {
        withdrawOrder(vehicle, immediateAbort, false);
    }

    /**
     * Notifies the dispatcher that any order a given vehicle might be processing
     * is to be withdrawn and the vehicle stopped.
     *
     * @param vehicle        The vehicle whose order is withdrawn.
     * @param immediateAbort Whether the vehicle's order should be aborted
     *                       immediately instead of withdrawn.
     * @param disableVehicle Whether to set the vehicle's processing state to
     *                       UNAVAILABLE after withdrawing the order to prevent the vehicle being
     *                       dispatched for now.
     */
    void withdrawOrder(@Nonnull Vehicle vehicle, boolean immediateAbort, boolean disableVehicle);

    /**
     * Notifies the dispatcher of changes in the topology.
     */
    default void topologyChanged() {
    }

}
