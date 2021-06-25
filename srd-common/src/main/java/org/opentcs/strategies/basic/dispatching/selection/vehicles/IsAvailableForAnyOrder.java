package org.opentcs.strategies.basic.dispatching.selection.vehicles;

import com.google.common.collect.Sets;
import com.seer.srd.model.BlockType;
import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.service.PlantModelService;
import com.seer.srd.route.service.TransportOrderService;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.components.kernel.services.SchedulerService;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.strategies.basic.dispatching.OrderReservationPool;
import org.opentcs.strategies.basic.dispatching.selection.VehicleSelectionFilter;

import javax.inject.Inject;
import java.util.HashSet;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Filters vehicles that are generally available for transport orders.
 */
public class IsAvailableForAnyOrder implements VehicleSelectionFilter {

    private final OrderReservationPool orderReservationPool;

    private final SchedulerService schedulerService;

    @Inject
    public IsAvailableForAnyOrder(OrderReservationPool orderReservationPool,
                                  SchedulerService schedulerService) {
        this.orderReservationPool = requireNonNull(orderReservationPool, "orderReservationPool");
        this.schedulerService = requireNonNull(schedulerService, "schedulerService");
    }

    @Override
    public boolean test(Vehicle vehicle) {
        return vehicle.getIntegrationLevel() == Vehicle.IntegrationLevel.TO_BE_UTILIZED
                && vehicle.getCurrentPosition() != null
                && vehicle.getOrderSequence() == null
                && (!RouteConfigKt.getRouteConfig().getNewCommAdapter() || vehicle.isDominating())
                && !vehicle.isEnergyLevelCritical()
                && !needsMoreCharging(vehicle)
                && (processesNoOrder(vehicle)
                || processesDispensableOrder(vehicle))
                && !hasOrderReservation(vehicle)
                && !inSameDirectionBlock(vehicle);
    }

    private boolean needsMoreCharging(Vehicle vehicle) {
        return vehicle.hasState(Vehicle.State.CHARGING)
                && !rechargeThresholdReached(vehicle);
    }

    private boolean rechargeThresholdReached(Vehicle vehicle) {
        return RouteConfigKt.getRouteConfig().getDispatcher().getKeepRechargingUntilFullyCharged()
                ? vehicle.isEnergyLevelFullyRecharged()
                : vehicle.isEnergyLevelSufficientlyRecharged();
    }

    private boolean processesNoOrder(Vehicle vehicle) {
        return vehicle.hasProcState(Vehicle.ProcState.IDLE)
                && (vehicle.hasState(Vehicle.State.IDLE)
                || vehicle.hasState(Vehicle.State.CHARGING));
    }

    private boolean processesDispensableOrder(Vehicle vehicle) {
        return vehicle.hasProcState(Vehicle.ProcState.PROCESSING_ORDER)
                && !vehicle.hasState(Vehicle.State.ERROR)
                && !vehicle.hasState(Vehicle.State.UNAVAILABLE)
                && Vehicle.IntegrationLevel.TO_BE_UTILIZED.equals(vehicle.getIntegrationLevel())
                && isDispensableOfVehicleOrder(vehicle);
    }

    private boolean isDispensableOfVehicleOrder(Vehicle vehicle) {
        TransportOrder order = TransportOrderService.INSTANCE.getOrderOrNull(vehicle.getTransportOrder());
        return order != null && order.isDispensable();
    }

    private boolean hasOrderReservation(Vehicle vehicle) {
        return !orderReservationPool.findReservations(vehicle.getName()).isEmpty();
    }

    private boolean inSameDirectionBlock(Vehicle vehicle) {
        if (schedulerService.fetchSchedulerAllocations().getAllocationStates().get(vehicle.getName()) == null) {
            return false;
        }
        Set<String> allocatedResources =
                new HashSet<>(schedulerService.fetchSchedulerAllocations().getAllocationStates().get(vehicle.getName()));
        return PlantModelService.INSTANCE.getPlantModel().getBlocks().values().stream()
                .filter(block -> block.getType().equals(BlockType.SAME_DIRECTION_ONLY))
                .anyMatch(block -> !Sets.intersection(block.getMembers(), allocatedResources).isEmpty());
    }
}
