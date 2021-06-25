package org.opentcs.strategies.basic.dispatching.selection.vehicles;

import com.seer.srd.model.Point;
import com.seer.srd.route.DeadlockState;
import com.seer.srd.route.RouteConfig;
import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.service.PlantModelService;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.strategies.basic.dispatching.OrderReservationPool;
import org.opentcs.strategies.basic.dispatching.selection.ParkVehicleSelectionFilter;

import javax.inject.Inject;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

/**
 * Filters vehicles that are parkable.
 */
public class IsParkable implements ParkVehicleSelectionFilter {

    private final OrderReservationPool orderReservationPool;

    @Inject
    public IsParkable(OrderReservationPool orderReservationPool) {
        this.orderReservationPool = requireNonNull(orderReservationPool, "orderReservationPool");
    }

    @Override
    public boolean test(Vehicle vehicle) {
        return vehicle.getIntegrationLevel() == Vehicle.IntegrationLevel.TO_BE_UTILIZED
                && vehicle.hasProcState(Vehicle.ProcState.IDLE)
                && (vehicle.hasState(Vehicle.State.IDLE) || (vehicle.hasState(Vehicle.State.CHARGING)
                && vehicle.isEnergyLevelFullyRecharged()
                && RouteConfigKt.getRouteConfig().getDispatcher().getParkVehicleWhenFullyCharged()))
                && vehicle.getCurrentPosition() != null
                && !isParkingPosition(vehicle.getCurrentPosition())
                && vehicle.getOrderSequence() == null
                && orderReservationPool.findReservations(vehicle.getName()).isEmpty()
                && !DeadlockState.INSTANCE.getSolvingDeadlock()
                && (!RouteConfigKt.getRouteConfig().getNewCommAdapter() || vehicle.isDominating())
                && System.currentTimeMillis() - vehicle.getLastTerminateTimeMs() > RouteConfigKt.getRouteConfig().getDispatcher().getParkAndRechargeDelayMs();
    }

    private boolean isParkingPosition(String pointName) {
        if (pointName == null) return false;
        Point position = PlantModelService.INSTANCE.getPlantModel().getPoints().get(pointName);
        return position.isParkingPosition();
    }
}
