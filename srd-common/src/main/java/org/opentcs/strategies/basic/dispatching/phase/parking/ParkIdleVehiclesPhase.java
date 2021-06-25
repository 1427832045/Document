package org.opentcs.strategies.basic.dispatching.phase.parking;

import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.service.VehicleService;
import org.opentcs.components.kernel.Router;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.strategies.basic.dispatching.TransportOrderUtil;
import org.opentcs.strategies.basic.dispatching.selection.candidates.CompositeAssignmentCandidateSelectionFilter;
import org.opentcs.strategies.basic.dispatching.selection.vehicles.CompositeParkVehicleSelectionFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Creates parking orders for idle vehicles not already at a parking position considering all
 * parking positions.
 */
public class ParkIdleVehiclesPhase extends AbstractParkingPhase {

    private static final Logger LOG = LoggerFactory.getLogger(ParkIdleVehiclesPhase.class);
    /**
     * A filter for selecting vehicles that may be parked.
     */
    private final CompositeParkVehicleSelectionFilter vehicleSelectionFilter;

    @Inject
    @SuppressWarnings("deprecation")
    public ParkIdleVehiclesPhase(
            org.opentcs.components.kernel.ParkingPositionSupplier parkingPosSupplier,
            Router router,
            CompositeAssignmentCandidateSelectionFilter assignmentCandidateSelectionFilter,
            TransportOrderUtil transportOrderUtil,
            CompositeParkVehicleSelectionFilter vehicleSelectionFilter) {
        super(parkingPosSupplier,
                router,
                assignmentCandidateSelectionFilter,
                transportOrderUtil);
        this.vehicleSelectionFilter = requireNonNull(vehicleSelectionFilter, "vehicleSelectionFilter");
    }

    @Override
    public void run() {
        if (!RouteConfigKt.getRouteConfig().getDispatcher().getParkIdleVehicles()) {
            return;
        }

        LOG.debug("Looking for vehicles to send to parking positions...");

        for (Vehicle vehicle : VehicleService.INSTANCE.listVehicles().stream().filter(vehicleSelectionFilter).collect(Collectors.toSet())) {
            createParkingOrder(vehicle);
        }
    }
}
