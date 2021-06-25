package org.opentcs.strategies.basic.dispatching.phase.parking;

import com.seer.srd.model.Point;
import org.opentcs.components.kernel.Router;
import com.seer.srd.vehicle.Vehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;
import static java.util.Objects.requireNonNull;
import static org.opentcs.components.kernel.Dispatcher.PROPKEY_ASSIGNED_PARKING_POSITION;
import static org.opentcs.components.kernel.Dispatcher.PROPKEY_PREFERRED_PARKING_POSITION;

/**
 * A parking position supplier that tries to find parking positions that are unoccupied,
 * not on the current route of any other vehicle and as close as possible to the
 * parked vehicle's current position.
 *
 * @author Youssef Zaki (Fraunhofer IML)
 * @author Stefan Walter (Fraunhofer IML)
 */
public class DefaultParkingPositionSupplier
        extends AbstractParkingPositionSupplier {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultParkingPositionSupplier.class);

    @Inject
    public DefaultParkingPositionSupplier(Router router) {
        super(router);
    }

    @Override
    public Optional<Point> findParkingPosition(final Vehicle vehicle) {
        requireNonNull(vehicle, "vehicle");

        if (vehicle.getCurrentPosition() == null) {
            return Optional.empty();
        }

        Set<Point> parkingPosCandidates = findUsableParkingPositions(vehicle);

        if (parkingPosCandidates.isEmpty()) {
            LOG.debug("No parking position candidates found.");
            return Optional.empty();
        }

        // Check if the vehicle has an assigned parking position.
        // If yes, return either that (if it's with the available points) or none.
        String assignedParkingPosName = vehicle.getProperties().get(PROPKEY_ASSIGNED_PARKING_POSITION);
        String vehiclePos = vehicle.getCurrentPosition();
        if (assignedParkingPosName != null && vehiclePos != null) {
            if (router.getCostsByPointRef(vehicle, vehiclePos, assignedParkingPosName) < Long.MAX_VALUE) {
                return Optional.ofNullable(pickPointWithName(assignedParkingPosName, parkingPosCandidates));
            } else {
                return Optional.empty();
            }
        }

        String preferredParkingPosNames = vehicle.getProperties().get(PROPKEY_PREFERRED_PARKING_POSITION);
        if (preferredParkingPosNames != null) {
            List<String> preferredNames = Arrays.asList(preferredParkingPosNames.split(","));
            Set<Point> preferredParkingPositions =
                    preferredNames.stream()
                            .map(name -> pickPointWithName(name, parkingPosCandidates))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());
            if (!preferredParkingPositions.isEmpty()) {
                Point nearestPreferredPoint = nearestPoint(vehicle, preferredParkingPositions);
                LOG.debug("Selected parking position {} for vehicle {} from preferred candidates {}.",
                        nearestPreferredPoint,
                        vehicle.getName(),
                        preferredParkingPositions);
                return Optional.ofNullable(nearestPreferredPoint);
            }
        }

        Point nearestPoint = nearestPoint(vehicle, parkingPosCandidates);
        LOG.debug("Selected parking position {} for vehicle {} from candidates {}.",
                nearestPoint,
                vehicle.getName(),
                parkingPosCandidates);
        return Optional.ofNullable(nearestPoint);
    }

    @Nullable
    private Point pickPointWithName(String name, Set<Point> points) {
        return points.stream()
                .filter(point -> name.equals(point.getName()))
                .findAny()
                .orElse(null);
    }
}
