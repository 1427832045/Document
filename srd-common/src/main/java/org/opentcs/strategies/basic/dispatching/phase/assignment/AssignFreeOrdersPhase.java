package org.opentcs.strategies.basic.dispatching.phase.assignment;

import com.seer.srd.model.Point;
import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.service.PlantModelService;
import com.seer.srd.route.service.TransportOrderService;
import com.seer.srd.route.service.VehicleService;
import org.opentcs.components.kernel.Router;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.strategies.basic.dispatching.*;
import org.opentcs.strategies.basic.dispatching.priorization.CompositeOrderCandidateComparator;
import org.opentcs.strategies.basic.dispatching.priorization.CompositeOrderComparator;
import org.opentcs.strategies.basic.dispatching.priorization.CompositeVehicleCandidateComparator;
import org.opentcs.strategies.basic.dispatching.priorization.CompositeVehicleComparator;
import org.opentcs.strategies.basic.dispatching.selection.candidates.CompositeAssignmentCandidateSelectionFilter;
import org.opentcs.strategies.basic.dispatching.selection.orders.CompositeTransportOrderSelectionFilter;
import org.opentcs.strategies.basic.dispatching.selection.vehicles.CompositeVehicleSelectionFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Assigns transport orders to vehicles that are currently not processing any and are not bound to
 * any order sequences.
 */
public class AssignFreeOrdersPhase implements Phase {

    private static final Logger LOG = LoggerFactory.getLogger(AssignFreeOrdersPhase.class);

    private final Router router;
    /**
     * Stores reservations of orders for vehicles.
     */
    private final OrderReservationPool orderReservationPool;
    /**
     * Defines the order of vehicles when there are less vehicles than transport orders.
     */
    private final Comparator<Vehicle> vehicleComparator;
    /**
     * Defines the order of transport orders when there are less transport orders than vehicles.
     */
    private final Comparator<TransportOrder> orderComparator;
    /**
     * Sorts candidates when looking for a transport order to be assigned to a vehicle.
     */
    private final Comparator<AssignmentCandidate> orderCandidateComparator;
    /**
     * Sorts candidates when looking for a vehicle to be assigned to a transport order.
     */
    private final Comparator<AssignmentCandidate> vehicleCandidateComparator;
    /**
     * A collection of predicates for filtering vehicles.
     */
    private final CompositeVehicleSelectionFilter vehicleSelectionFilter;
    /**
     * A collection of predicates for filtering transport orders.
     */
    private final CompositeTransportOrderSelectionFilter transportOrderSelectionFilter;
    /**
     * A collection of predicates for filtering assignment candidates.
     */
    private final CompositeAssignmentCandidateSelectionFilter assignmentCandidateSelectionFilter;

    private final TransportOrderUtil transportOrderUtil;

    private final KMAssignUtil kmAssignUtil;

    private boolean initialized;

    @Inject
    public AssignFreeOrdersPhase(
            Router router,
            OrderReservationPool orderReservationPool,
            CompositeVehicleComparator vehicleComparator,
            CompositeOrderComparator orderComparator,
            CompositeOrderCandidateComparator orderCandidateComparator,
            CompositeVehicleCandidateComparator vehicleCandidateComparator,
            CompositeVehicleSelectionFilter vehicleSelectionFilter,
            CompositeTransportOrderSelectionFilter transportOrderSelectionFilter,
            CompositeAssignmentCandidateSelectionFilter assignmentCandidateSelectionFilter,
            TransportOrderUtil transportOrderUtil,
            KMAssignUtil kmAssignUtil) {
        this.router = requireNonNull(router, "router");
        this.orderReservationPool = requireNonNull(orderReservationPool, "orderReservationPool");
        this.vehicleComparator = requireNonNull(vehicleComparator, "vehicleComparator");
        this.orderComparator = requireNonNull(orderComparator, "orderComparator");
        this.orderCandidateComparator = requireNonNull(orderCandidateComparator,
                "orderCandidateComparator");
        this.vehicleCandidateComparator = requireNonNull(vehicleCandidateComparator,
                "vehicleCandidateComparator");
        this.vehicleSelectionFilter = requireNonNull(vehicleSelectionFilter, "vehicleSelectionFilter");
        this.transportOrderSelectionFilter = requireNonNull(transportOrderSelectionFilter,
                "transportOrderSelectionFilter");
        this.assignmentCandidateSelectionFilter = requireNonNull(assignmentCandidateSelectionFilter,
                "assignmentCandidateSelectionFilter");
        this.transportOrderUtil = requireNonNull(transportOrderUtil, "transportOrderUtil");
        this.kmAssignUtil = requireNonNull(kmAssignUtil, "hungarianAssignUtil");
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
        if (RouteConfigKt.getRouteConfig().getDispatcher().getDispatchForSubprojects()) {
            runSubprojectDispatch();
        } else {
            runNormalDispatch();
        }
    }

    private void assign(Set<Vehicle> vehicles, Set<TransportOrder> orders) {
        if (RouteConfigKt.getRouteConfig().getUseKMAssign()) {
            LOG.debug("Using Kuhn-Munkres assign");
            kmAssignUtil.assign(vehicles, orders);
        } else {
            LOG.debug("Not using Kuhn-Munkres assign");
            if (vehicles.size() < orders.size()) {
                LOG.debug("Assign order to vehicle.");
                vehicles.stream().sorted(vehicleComparator).forEach(this::tryAssignOrder);
            } else {
                LOG.debug("Assign vehicle to order.");
                orders.stream().sorted(orderComparator).forEach(this::tryAssignVehicle);
            }

        }
    }

    private void runNormalDispatch() {
        Set<Vehicle> availableVehicles = VehicleService.INSTANCE.listVehicles().stream().filter(vehicleSelectionFilter).collect(Collectors.toSet());
        if (availableVehicles.isEmpty()) {
            LOG.debug("No vehicles available, skipping potentially expensive fetching of orders.");
            return;
        }
        Set<TransportOrder> availableOrders = TransportOrderService.INSTANCE.listUnfinishedOrders()
                .stream().filter(transportOrderSelectionFilter).collect(Collectors.toSet());

        LOG.debug("Available for dispatching: {} transport orders and {} vehicles.",
                availableOrders.size(),
                availableVehicles.size());
        assign(availableVehicles, availableOrders);
    }

    private void runSubprojectDispatch() {
        Set<Vehicle> availableVehicles = VehicleService.INSTANCE.listVehicles().stream().filter(vehicleSelectionFilter).collect(Collectors.toSet());
        if (availableVehicles.isEmpty()) {
            LOG.debug("No vehicles available, skipping potentially expensive fetching of orders.");
            return;
        }

        Set<TransportOrder> availableOrders = TransportOrderService.INSTANCE.listUnfinishedOrders()
                .stream().filter(transportOrderSelectionFilter).collect(Collectors.toSet());

        LOG.debug("Available for dispatching: {} transport orders and {} vehicles.",
                availableOrders.size(),
                availableVehicles.size());
        Map<String, Set<TransportOrder>> groupOrderMap = availableOrders.stream()
                .collect(Collectors.groupingBy(TransportOrder::getCategory, Collectors.toSet()));

        groupOrderMap.forEach((key, value) -> {
            LOG.debug("Dispatch group {} contains orders: {}", key,
                    value.stream().map(TransportOrder::getName).collect(Collectors.toSet()));
            Set<Vehicle> groupVehicles = availableVehicles.stream()
                    .filter(v -> v.getProcessableCategories().contains(key))
                    .filter(vehicleSelectionFilter)
                    .collect(Collectors.toSet());
            LOG.debug("Dispatch group {} contains vehicles: {}", key,
                    groupVehicles.stream().map(Vehicle::getName).collect(Collectors.toSet()));
            if (groupVehicles.isEmpty()) {
                LOG.debug("No vehicles available for order group {}", key);
                return;
            }
            assign(groupVehicles, value);
        });
    }

    private void tryAssignOrder(Vehicle vehicle) {
        LOG.debug("Trying to find transport order for vehicle '{}'...", vehicle.getName());

        Point vehiclePosition = PlantModelService.INSTANCE.getPlantModel().getPoints().get(vehicle.getCurrentPosition());

        TransportOrderService.INSTANCE.listUnfinishedOrders().stream().filter((order) -> dispatchableForVehicle(order, vehicle))
                .map(order -> computeCandidate(vehicle, vehiclePosition, order))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(assignmentCandidateSelectionFilter).min(orderCandidateComparator)
                .ifPresent(this::assignOrder);
    }

    private void tryAssignVehicle(TransportOrder order) {
        LOG.debug("Trying to find vehicle for transport order '{}'...", order.getName());

        VehicleService.INSTANCE.listVehicles().stream().filter(vehicle -> availableForOrder(vehicle, order))
                .map(vehicle -> computeCandidate(vehicle, PlantModelService.INSTANCE.getPlantModel()
                        .getPoints().get(vehicle.getCurrentPosition()), order))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(assignmentCandidateSelectionFilter).min(vehicleCandidateComparator)
                .ifPresent(this::assignOrder);
    }

    private void assignOrder(AssignmentCandidate candidate) {
        // If the vehicle currently has a (dispensable) order, we may not assign the new one here
        // directly, but must abort the old one (DefaultDispatcher.abortOrder()) and wait for the
        // vehicle's ProcState to become IDLE.
        if (candidate.getVehicle().getTransportOrder() == null) {
            LOG.debug("Assigning transport order '{}' to vehicle '{}'...",
                    candidate.getTransportOrder().getName(),
                    candidate.getVehicle().getName());
            transportOrderUtil.assignTransportOrder(candidate.getVehicle(),
                    candidate.getTransportOrder(),
                    candidate.getDriveOrders());
        } else {
            LOG.debug("Reserving transport order '{}' for vehicle '{}'...",
                    candidate.getTransportOrder().getName(),
                    candidate.getVehicle().getName());
            // Remember that the new order is reserved for this vehicle.
            orderReservationPool.addReservation(candidate.getTransportOrder().getName(),
                    candidate.getVehicle().getName());
            transportOrderUtil.abortOrder(candidate.getVehicle(), false, false, false);
        }
    }

    private Optional<AssignmentCandidate> computeCandidate(Vehicle vehicle,
                                                           Point vehiclePosition,
                                                           TransportOrder order) {
        return router.getRoute(vehicle, vehiclePosition, order)
                .map(driveOrders -> new AssignmentCandidate(vehicle, order, driveOrders));
    }

    private boolean dispatchableForVehicle(TransportOrder order, Vehicle vehicle) {
        // We only want to check dispatchable transport orders.
        // Filter out transport orders that are intended for other vehicles.
        return transportOrderSelectionFilter.test(order) && orderAssignableToVehicle(order, vehicle);
    }

    private boolean availableForOrder(Vehicle vehicle, TransportOrder order) {
        return vehicleSelectionFilter.test(vehicle) && orderAssignableToVehicle(order, vehicle);
    }

    private boolean orderAssignableToVehicle(TransportOrder order, Vehicle vehicle) {
        return order.getIntendedVehicle() == null || Objects.equals(order.getIntendedVehicle(), vehicle.getName());
    }
}
