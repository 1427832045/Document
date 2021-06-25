package org.opentcs.strategies.basic.dispatching.phase.parking;

import com.seer.srd.model.Point;
import com.seer.srd.route.service.PlantModelService;
import com.seer.srd.route.service.VehicleService;
import org.opentcs.components.kernel.Router;
import com.seer.srd.vehicle.Vehicle;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * An abstract base class for parking position suppliers.
 */
public abstract class AbstractParkingPositionSupplier
        implements ParkingPositionSupplier {

    protected final Router router;

    private boolean initialized;

    protected AbstractParkingPositionSupplier(Router router) {
        this.router = requireNonNull(router, "router");
    }

    @Override
    public void initialize() {
        if (initialized) {
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
        if (!initialized) {
            return;
        }

        initialized = false;
    }

    public Router getRouter() {
        return router;
    }

    /**
     * Returns a set of parking positions usable for the given vehicle (usable in the sense that these
     * positions are not occupied by other vehicles).
     *
     * @param vehicle The vehicles to find parking positions for.
     * @return The set of usable parking positions.
     */
    protected Set<Point> findUsableParkingPositions(Vehicle vehicle) {
        // Find out which points are destination points of the current routes of
        // all vehicles, and keep them. (Multiple lookups ahead.)
        Set<Point> targetedPoints = getRouter().getTargetedPoints();

        return fetchAllParkingPositions().stream()
                .filter(point -> isPointUnoccupiedFor(point, vehicle, targetedPoints))
                .collect(Collectors.toSet());
    }

    /**
     * Returns from the given set of points the one that is nearest to the given
     * vehicle.
     *
     * @param vehicle The vehicle.
     * @param points  The set of points to select the nearest one from.
     * @return The point nearest to the given vehicle.
     */
    @Nullable
    protected Point nearestPoint(Vehicle vehicle, Set<Point> points) {
        requireNonNull(vehicle, "vehicle");
        requireNonNull(points, "points");

        if (vehicle.getCurrentPosition() == null) {
            return null;
        }

        Point vehiclePos = PlantModelService.INSTANCE.getPlantModel().getPoints().get(vehicle.getCurrentPosition());

        return points.stream()
                .map(point -> parkingPositionCandidate(vehicle, vehiclePos, point))
                .filter(candidate -> candidate.costs < Long.MAX_VALUE)
                .min(Comparator.comparingLong(candidate -> candidate.costs))
                .map(candidate -> candidate.point)
                .orElse(null);
    }

    /**
     * Gathers a set of all points from all blocks that the given point is a member of.
     *
     * @param point The point to check.
     * @return A set of all points from all blocks that the given point is a member of.
     */
    protected Set<Point> expandPoints(Point point) {
        return PlantModelService.INSTANCE.expandResources(Collections.singleton(point.getName()))
                .stream()
                .filter(PlantModelService.INSTANCE::isNameOfPoint)
                .map(resource -> PlantModelService.INSTANCE.getPlantModel().getPoints().get(resource))
                .collect(Collectors.toSet());
    }

    protected Set<Point> fetchAllParkingPositions() {
        return PlantModelService.INSTANCE.getPlantModel().getPoints().values()
                .stream().filter(Point::isParkingPosition).collect(Collectors.toSet());
    }

    /**
     * Checks if ALL points within the same block as the given access point are NOT occupied or
     * targeted by any other vehicle than the given one.
     *
     * @param accessPoint    The point to be checked.
     * @param vehicle        The vehicle to be checked for.
     * @param targetedPoints All currently known targeted points.
     * @return <code>true</code> if, and only if, ALL points within the same block as the given access
     * point are NOT occupied or targeted by any other vehicle than the given one.
     */
    private boolean isPointUnoccupiedFor(Point accessPoint,
                                         Vehicle vehicle,
                                         Set<Point> targetedPoints) {
        return expandPoints(accessPoint).stream()
                .allMatch(point -> !pointOccupiedOrTargetedByOtherVehicle(point,
                        vehicle,
                        targetedPoints));
    }

    private boolean pointOccupiedOrTargetedByOtherVehicle(Point pointToCheck,
                                                          Vehicle vehicle,
                                                          Set<Point> targetedPoints) {
        Optional<Vehicle> tempV = VehicleService.INSTANCE.listVehicles()
                .stream()
                .filter(v -> !v.getName().equals(vehicle.getName()) &&
                        v.getCurrentPosition() != null &&
                        pointToCheck.getName().equals(v.getCurrentPosition()))
                .findAny();
        if (tempV.isPresent()) {
            return true;
        } else if (pointToCheck.getOccupyingVehicle() != null
                && !pointToCheck.getOccupyingVehicle().equals(vehicle.getName())) {
            return true;
        } else return targetedPoints.contains(pointToCheck);
    }

    private PointCandidate parkingPositionCandidate(Vehicle vehicle,
                                                    Point srcPosition,
                                                    Point destPosition) {
        return new PointCandidate(destPosition, router.getCosts(vehicle, srcPosition, destPosition));
    }

    private static class PointCandidate {

        private final Point point;
        private final long costs;

        public PointCandidate(Point point, long costs) {
            this.point = point;
            this.costs = costs;
        }
    }
}
