package org.opentcs.strategies.basic.dispatching.selection.vehicles;

import com.seer.srd.model.Point;
import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.service.PlantModelService;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.strategies.basic.dispatching.OrderReservationPool;
import org.opentcs.strategies.basic.dispatching.selection.ReparkVehicleSelectionFilter;

import javax.inject.Inject;

import static java.util.Objects.requireNonNull;

/**
 * Filters vehicles that are reparkable.
 */
public class IsReparkable implements ReparkVehicleSelectionFilter {

    private final OrderReservationPool orderReservationPool;

    @Inject
    public IsReparkable(OrderReservationPool orderReservationPool) {
        this.orderReservationPool = requireNonNull(orderReservationPool, "orderReservationPool");
    }

    @Override
    public boolean test(Vehicle vehicle) {
        return vehicle.getIntegrationLevel() == Vehicle.IntegrationLevel.TO_BE_UTILIZED
                && vehicle.hasProcState(Vehicle.ProcState.IDLE)
                && vehicle.hasState(Vehicle.State.IDLE)
                && (!RouteConfigKt.getRouteConfig().getNewCommAdapter() || vehicle.isDominating())
                && isParkingPosition(vehicle.getCurrentPosition())
                && vehicle.getOrderSequence() == null
                && orderReservationPool.findReservations(vehicle.getName()).isEmpty();
    }

    private boolean isParkingPosition(String pointName) {
        if (pointName == null) return false;
        Point position = PlantModelService.INSTANCE.getPlantModel().getPoints().get(pointName);
        return position.isParkingPosition();
    }
}
