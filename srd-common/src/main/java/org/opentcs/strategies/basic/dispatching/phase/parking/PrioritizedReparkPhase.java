package org.opentcs.strategies.basic.dispatching.phase.parking;

import com.seer.srd.model.Point;
import com.seer.srd.route.DispatcherConfiguration;
import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.service.PlantModelService;
import com.seer.srd.route.service.VehicleService;
import org.opentcs.components.kernel.Router;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.strategies.basic.dispatching.TransportOrderUtil;
import org.opentcs.strategies.basic.dispatching.selection.candidates.CompositeAssignmentCandidateSelectionFilter;
import org.opentcs.strategies.basic.dispatching.selection.vehicles.CompositeReparkVehicleSelectionFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Creates parking orders for idle vehicles already at a parking position to send them to higher
 * prioritized parking positions.
 */
public class PrioritizedReparkPhase extends AbstractParkingPhase {

    private static final Logger LOG = LoggerFactory.getLogger(PrioritizedReparkPhase.class);

    private final CompositeReparkVehicleSelectionFilter vehicleSelectionFilter;
    private final ParkingPositionPriorityComparator priorityComparator;

    @Inject
    public PrioritizedReparkPhase(
            PrioritizedParkingPositionSupplier parkingPosSupplier,
            Router router,
            CompositeAssignmentCandidateSelectionFilter assignmentCandidateSelectionFilter,
            TransportOrderUtil transportOrderUtil,
            CompositeReparkVehicleSelectionFilter vehicleSelectionFilter,
            ParkingPositionPriorityComparator priorityComparator) {
        super(parkingPosSupplier,
                router,
                assignmentCandidateSelectionFilter,
                transportOrderUtil);
        this.vehicleSelectionFilter = requireNonNull(vehicleSelectionFilter, "vehicleSelectionFilter");
        this.priorityComparator = requireNonNull(priorityComparator, "priorityComparator");
    }

    @Override
    public void run() {
        DispatcherConfiguration cfg = RouteConfigKt.getRouteConfig().getDispatcher();
        if (!cfg.getParkIdleVehicles() || !cfg.getConsiderParkingPositionPriorities()
                || !cfg.getReparkVehiclesToHigherPriorityPositions()) {
            return;
        }

        LOG.debug("Looking for parking vehicles to send to higher prioritized parking positions...");

        Set<Vehicle> vehicles = VehicleService.INSTANCE.listVehicles().stream().filter(vehicleSelectionFilter).collect(Collectors.toSet());
        vehicles.stream()
                .sorted((vehicle1, vehicle2) -> {
                    // Sort the vehicles based on the priority of the parking position they occupy
                    Map<String, Point> points = PlantModelService.INSTANCE.getPlantModel().getPoints();
                    Point point1 = points.get(vehicle1.getCurrentPosition());
                    Point point2 = points.get(vehicle2.getCurrentPosition());
                    return priorityComparator.compare(point1, point2);
                })
                .forEach(vehicle -> createParkingOrder(vehicle));
    }
}
