package org.opentcs.strategies.basic.dispatching.phase.parking;

import com.seer.srd.model.Point;
import com.seer.srd.route.service.PlantModelService;
import org.opentcs.components.kernel.Router;
import com.seer.srd.vehicle.Vehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static org.opentcs.util.Assertions.checkArgument;

/**
 * A parking position supplier that tries to find the parking position with the highest priority
 * that is unoccupied, not on the current route of any other vehicle and as close as possible to the
 * vehicle's current position.
 */
public class PrioritizedParkingPositionSupplier
        extends AbstractParkingPositionSupplier {

    private static final Logger LOG = LoggerFactory.getLogger(PrioritizedParkingPositionSupplier.class);
    /**
     * A function computing the priority of a parking position.
     */
    private final ParkingPositionToPriorityFunction priorityFunction;

    @Inject
    public PrioritizedParkingPositionSupplier(Router router,
                                              ParkingPositionToPriorityFunction priorityFunction
    ) {
        super(router);
        this.priorityFunction = requireNonNull(priorityFunction, "priorityFunction");
    }

    @Override
    public Optional<Point> findParkingPosition(final Vehicle vehicle) {
        requireNonNull(vehicle, "vehicle");

        if (vehicle.getCurrentPosition() == null) {
            return Optional.empty();
        }

        int currentPriority = priorityOfCurrentPosition(vehicle);
        Set<Point> parkingPosCandidates = findUsableParkingPositions(vehicle).stream()
                .filter(point -> hasHigherPriorityThan(point, currentPriority))
                .collect(Collectors.toSet());

        if (parkingPosCandidates.isEmpty()) {
            LOG.debug("{}: No parking position candidates found.", vehicle.getName());
            return Optional.empty();
        }

        LOG.debug("{}: Selecting parking position from candidates {}.",
                vehicle.getName(),
                parkingPosCandidates);

        parkingPosCandidates = filterPositionsWithHighestPriority(parkingPosCandidates);
        Point parkingPos = nearestPoint(vehicle, parkingPosCandidates);

        LOG.debug("{}: Selected parking position {}.", vehicle.getName(), parkingPos);

        return Optional.ofNullable(parkingPos);
    }

    private int priorityOfCurrentPosition(Vehicle vehicle) {
        Point currentPos = PlantModelService.INSTANCE.getPlantModel().getPoints().get(vehicle.getCurrentPosition());
        return priorityFunction
                .andThen(priority -> priority != null ? priority : Integer.MAX_VALUE)
                .apply(currentPos);
    }

    private boolean hasHigherPriorityThan(Point point, Integer priority) {
        Integer pointPriority = priorityFunction.apply(point);
        if (pointPriority == null) {
            return false;
        }

        return pointPriority < priority;
    }

    private Set<Point> filterPositionsWithHighestPriority(Set<Point> positions) {
        checkArgument(!positions.isEmpty(), "'positions' must not be empty");

        Map<Integer, List<Point>> prioritiesToPositions = positions.stream()
                .collect(Collectors.groupingBy(point -> priorityFunction.apply(point)));

        Integer highestPriority = prioritiesToPositions.keySet().stream()
                .reduce(Integer::min)
                .get();

        return new HashSet<>(prioritiesToPositions.get(highestPriority));
    }
}
