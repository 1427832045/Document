package org.opentcs.strategies.basic.dispatching.phase.parking;

import com.seer.srd.model.Point;
import com.seer.srd.route.service.PlantModelService;
import org.opentcs.access.to.order.DestinationCreationTO;
import org.opentcs.access.to.order.TransportOrderCreationTO;
import org.opentcs.components.kernel.Router;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.data.order.Destination;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.data.order.TransportOrderState;
import org.opentcs.strategies.basic.dispatching.AssignmentCandidate;
import org.opentcs.strategies.basic.dispatching.Phase;
import org.opentcs.strategies.basic.dispatching.TransportOrderUtil;
import org.opentcs.strategies.basic.dispatching.selection.candidates.CompositeAssignmentCandidateSelectionFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * The base class for parking phases.
 */
public abstract class AbstractParkingPhase implements Phase {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractParkingPhase.class);
    /**
     * The strategy used for finding suitable parking positions.
     */
    @SuppressWarnings("deprecation")
    private final org.opentcs.components.kernel.ParkingPositionSupplier parkingPosSupplier;
    /**
     * The Router instance calculating route costs.
     */
    private final Router router;
    /**
     * A collection of predicates for filtering assignment candidates.
     */
    private final CompositeAssignmentCandidateSelectionFilter assignmentCandidateSelectionFilter;
    /**
     * Provides service functions for working with transport orders.
     */
    private final TransportOrderUtil transportOrderUtil;
    /**
     * Indicates whether this component is initialized.
     */
    private boolean initialized;

    @SuppressWarnings("deprecation")
    public AbstractParkingPhase(
            org.opentcs.components.kernel.ParkingPositionSupplier parkingPosSupplier,
            Router router,
            CompositeAssignmentCandidateSelectionFilter assignmentCandidateSelectionFilter,
            TransportOrderUtil transportOrderUtil) {
        this.router = requireNonNull(router, "router");
        this.parkingPosSupplier = requireNonNull(parkingPosSupplier, "parkingPosSupplier");
        this.assignmentCandidateSelectionFilter = requireNonNull(assignmentCandidateSelectionFilter,
                "assignmentCandidateSelectionFilter");
        this.transportOrderUtil = requireNonNull(transportOrderUtil, "transportOrderUtil");
    }

    @Override
    public void initialize() {
        if (isInitialized()) {
            return;
        }

        parkingPosSupplier.initialize();

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

        parkingPosSupplier.terminate();

        initialized = false;
    }

    protected void createParkingOrder(Vehicle vehicle) {
        Point vehiclePosition = PlantModelService.INSTANCE.getPlantModel().getPoints().get(vehicle.getCurrentPosition());

        // Get a suitable parking position for the vehicle.
        Optional<Point> parkPos = parkingPosSupplier.findParkingPosition(vehicle);
        LOG.debug("Parking position for {}: {}", vehicle, parkPos);
        // If we could not find a suitable parking position at all, just leave the vehicle where it is.
        if (!parkPos.isPresent()) {
            LOG.info("{}: Did not find a suitable parking position.", vehicle.getName());
            return;
        }
        // Create a destination for the point.
        List<DestinationCreationTO> parkDests = Arrays.asList(
                new DestinationCreationTO(parkPos.get().getName(), Destination.OP_PARK)
        );
        // Create a transport order for parking and verify its processability.
        TransportOrder parkOrder = com.seer.srd.route.service.TransportOrderService.INSTANCE.createTransportOrder(
                new TransportOrderCreationTO("Park-" + UUID.randomUUID(), parkDests)
                        .withDispensable(true)
                        .withIntendedVehicleName(vehicle.getName())
        );
        Optional<AssignmentCandidate> candidate = computeCandidate(vehicle, vehiclePosition, parkOrder)
                .filter(assignmentCandidateSelectionFilter);
        // XXX Change this to Optional.ifPresentOrElse() once we're at Java 9+.
        if (candidate.isPresent()) {
            transportOrderUtil.assignTransportOrder(candidate.get().getVehicle(),
                    candidate.get().getTransportOrder(),
                    candidate.get().getDriveOrders());
        } else {
            // Mark the order as failed, since the vehicle cannot execute it.
            com.seer.srd.route.service.TransportOrderService.INSTANCE.updateTransportOrderState(parkOrder.getName(), TransportOrderState.FAILED);
        }
    }

    private Optional<AssignmentCandidate> computeCandidate(Vehicle vehicle,
                                                           Point vehiclePosition,
                                                           TransportOrder order) {
        return router.getRoute(vehicle, vehiclePosition, order)
                .map(driveOrders -> new AssignmentCandidate(vehicle, order, driveOrders));
    }
}
