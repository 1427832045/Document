package org.opentcs.strategies.basic.dispatching.selection.vehicles;

import static java.util.Objects.requireNonNull;

import javax.inject.Inject;

import com.seer.srd.route.DeadlockState;
import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.strategies.basic.dispatching.selection.RechargeVehicleSelectionFilter;
import org.opentcs.strategies.basic.dispatching.OrderReservationPool;

/**
 * Filters vehicles that are idle and have a degraded energy level.
 */
public class IsIdleAndDegraded
        implements RechargeVehicleSelectionFilter {

    /**
     * Stores reservations of orders for vehicles.
     */
    private final OrderReservationPool orderReservationPool;

    /**
     * Creates a new instance.
     *
     * @param orderReservationPool Stores reservations of orders for vehicles.
     */
    @Inject
    public IsIdleAndDegraded(OrderReservationPool orderReservationPool) {
        this.orderReservationPool = requireNonNull(orderReservationPool, "orderReservationPool");
    }

    @Override
    public boolean test(Vehicle vehicle) {
        return idleAndDegraded(vehicle);
    }

    private boolean idleAndDegraded(Vehicle vehicle) {
        return vehicle.getIntegrationLevel() == Vehicle.IntegrationLevel.TO_BE_UTILIZED
                && vehicle.hasProcState(Vehicle.ProcState.IDLE)
                && vehicle.hasState(Vehicle.State.IDLE)
                && vehicle.getCurrentPosition() != null
                && vehicle.getOrderSequence() == null
                && vehicle.isEnergyLevelDegraded()
                && (!RouteConfigKt.getRouteConfig().getNewCommAdapter() || vehicle.isDominating())
                && orderReservationPool.findReservations(vehicle.getName()).isEmpty()
                && !DeadlockState.INSTANCE.getSolvingDeadlock()
                && System.currentTimeMillis() - vehicle.getLastTerminateTimeMs() > RouteConfigKt.getRouteConfig().getDispatcher().getParkAndRechargeDelayMs();
    }
}
