package org.opentcs.strategies.basic.dispatching.phase.parking;

import static java.util.Objects.requireNonNull;

import javax.inject.Inject;

import com.seer.srd.route.DispatcherConfiguration;
import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.service.VehicleService;
import org.opentcs.components.kernel.Router;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.strategies.basic.dispatching.TransportOrderUtil;
import org.opentcs.strategies.basic.dispatching.selection.candidates.CompositeAssignmentCandidateSelectionFilter;
import org.opentcs.strategies.basic.dispatching.selection.vehicles.CompositeParkVehicleSelectionFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

/**
 * Creates parking orders for idle vehicles not already at a parking position considering only
 * prioritized parking positions.
 */
public class PrioritizedParkingPhase extends AbstractParkingPhase {

    private static final Logger LOG = LoggerFactory.getLogger(PrioritizedParkingPhase.class);
    /**
     * A filter for selecting vehicles that may be parked.
     */
    private final CompositeParkVehicleSelectionFilter vehicleSelectionFilter;

    @Inject
    public PrioritizedParkingPhase(
            PrioritizedParkingPositionSupplier parkingPosSupplier,
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
        DispatcherConfiguration cfg = RouteConfigKt.getRouteConfig().getDispatcher();
        if (!cfg.getParkIdleVehicles() || !cfg.getConsiderParkingPositionPriorities()) {
            return;
        }

        LOG.debug("Looking for vehicles to send to prioritized parking positions...");

        for (Vehicle vehicle : VehicleService.INSTANCE.listVehicles().stream().filter(vehicleSelectionFilter).collect(Collectors.toSet())) {
            createParkingOrder(vehicle);
        }
    }
}
