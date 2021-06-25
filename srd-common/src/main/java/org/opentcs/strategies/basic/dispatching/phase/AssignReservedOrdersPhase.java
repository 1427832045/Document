package org.opentcs.strategies.basic.dispatching.phase;

import com.seer.srd.model.Point;
import com.seer.srd.route.service.PlantModelService;
import com.seer.srd.route.service.TransportOrderService;
import com.seer.srd.route.service.VehicleService;
import org.opentcs.components.kernel.Router;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.data.order.TransportOrderState;
import org.opentcs.strategies.basic.dispatching.AssignmentCandidate;
import org.opentcs.strategies.basic.dispatching.OrderReservationPool;
import org.opentcs.strategies.basic.dispatching.Phase;
import org.opentcs.strategies.basic.dispatching.TransportOrderUtil;
import org.opentcs.strategies.basic.dispatching.selection.candidates.CompositeAssignmentCandidateSelectionFilter;

import javax.inject.Inject;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Assigns reserved transport orders (if any) to vehicles that have just finished their withdrawn
 * ones.
 */
public class AssignReservedOrdersPhase implements Phase {

    private final Router router;
    /**
     * A collection of predicates for filtering assignment candidates.
     */
    private final CompositeAssignmentCandidateSelectionFilter assignmentCandidateSelectionFilter;
    /**
     * Stores reservations of orders for vehicles.
     */
    private final OrderReservationPool orderReservationPool;

    private final TransportOrderUtil transportOrderUtil;

    private boolean initialized;

    @Inject
    public AssignReservedOrdersPhase(
            Router router,
            CompositeAssignmentCandidateSelectionFilter assignmentCandidateSelectionFilter,
            OrderReservationPool orderReservationPool,
            TransportOrderUtil transportOrderUtil) {
        this.router = requireNonNull(router, "router");
        this.assignmentCandidateSelectionFilter = requireNonNull(assignmentCandidateSelectionFilter,
                "assignmentCandidateSelectionFilter");
        this.orderReservationPool = requireNonNull(orderReservationPool, "orderReservationPool");
        this.transportOrderUtil = requireNonNull(transportOrderUtil, "transportOrderUtil");
    }

    @Override
    public void initialize() {
        if (isInitialized()) {
            return;
        }
        initialized = true;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void terminate() {
        if (!isInitialized()) {
            return;
        }
        initialized = false;
    }

    @Override
    public void run() {
        for (Vehicle vehicle : VehicleService.INSTANCE.listVehicles().stream().filter(this::unavailable).collect(Collectors.toSet())) {
            orderReservationPool.removeReservations(vehicle.getName());
        }
        for (Vehicle vehicle : VehicleService.INSTANCE.listVehicles().stream().filter(this::available).collect(Collectors.toSet())) {
            checkForReservedOrder(vehicle);
        }
    }

    private void checkForReservedOrder(Vehicle vehicle) {
        // Check if there's an order reserved for this vehicle that is in an assignable state. If yes,
        // try to assign that.
        // Note that we expect no more than a single reserved order, and remove ALL reservations if we
        // find at least one, even if it cannot be processed by the vehicle in the end.
        orderReservationPool.findReservations(vehicle.getName()).stream()
                .map(TransportOrderService.INSTANCE::getOrderOrNull)
                .filter(order -> order.hasState(TransportOrderState.DISPATCHABLE))
                .limit(1)
                .peek(order -> orderReservationPool.removeReservations(vehicle.getName()))
                .map(order -> computeCandidate(vehicle, PlantModelService.INSTANCE.getPlantModel()
                        .getPoints().get(vehicle.getCurrentPosition()), order))
                .filter(optCandidate -> optCandidate.isPresent())
                .map(optCandidate -> optCandidate.get())
                .filter(assignmentCandidateSelectionFilter)
                .findFirst()
                .ifPresent(
                        candidate -> transportOrderUtil.assignTransportOrder(vehicle,
                                candidate.getTransportOrder(),
                                candidate.getDriveOrders())
                );
    }

    private boolean available(Vehicle vehicle) {
        return vehicle.getProcState() == Vehicle.ProcState.IDLE
                && (vehicle.getState() == Vehicle.State.IDLE || vehicle.getState() == Vehicle.State.CHARGING);
    }

    private boolean unavailable(Vehicle vehicle) {
        return vehicle.getState() == Vehicle.State.ERROR
                || vehicle.getState() == Vehicle.State.UNAVAILABLE
                || vehicle.getState() == Vehicle.State.UNKNOWN
                || !Vehicle.IntegrationLevel.TO_BE_UTILIZED.equals(vehicle.getIntegrationLevel());
    }

    private Optional<AssignmentCandidate> computeCandidate(Vehicle vehicle,
                                                           Point vehiclePosition,
                                                           TransportOrder order) {
        return router.getRoute(vehicle, vehiclePosition, order)
                .map(driveOrders -> new AssignmentCandidate(vehicle, order, driveOrders));
    }
}
