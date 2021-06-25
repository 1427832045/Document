package org.opentcs.strategies.basic.dispatching.phase.recharging;

import com.seer.srd.model.Link;
import com.seer.srd.model.Location;
import com.seer.srd.model.LocationType;
import com.seer.srd.model.Point;
import com.seer.srd.route.service.PlantModelService;
import com.seer.srd.route.service.VehicleService;
import org.opentcs.components.kernel.Router;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.data.order.Destination;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static org.opentcs.components.kernel.Dispatcher.PROPKEY_ASSIGNED_RECHARGE_LOCATION;
import static org.opentcs.components.kernel.Dispatcher.PROPKEY_PREFERRED_RECHARGE_LOCATION;

/**
 * Finds assigned, preferred or (routing-wise) cheapest recharge locations for vehicles.
 */
public class DefaultRechargePositionSupplier implements RechargePositionSupplier {

    private final Router router;

    private boolean initialized;

    @Inject
    public DefaultRechargePositionSupplier(Router router) {
        this.router = requireNonNull(router, "router");
    }

    @Override
    public void initialize() {
        if (initialized) return;
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

    @Override
    public List<Destination> findRechargeSequence(Vehicle vehicle) {
        requireNonNull(vehicle, "vehicle");

        if (vehicle.getCurrentPosition() == null) {
            return new ArrayList<>();
        }

        Map<Location, Set<Point>> rechargeLocations
                = findLocationsForOperation(vehicle.getRechargeOperation(), vehicle, router.getTargetedPoints());

        String assignedRechargeLocationName = vehicle.getProperties().get(PROPKEY_ASSIGNED_RECHARGE_LOCATION);
        if (assignedRechargeLocationName != null) {
            Location location = pickLocationWithName(assignedRechargeLocationName,
                    rechargeLocations.keySet());
            if (location == null) {
                return new ArrayList<>();
            }
            // 看下这个 location 是否可达
            Map<Location, Set<Point>> assignedLocations = rechargeLocations.entrySet()
                    .stream()
                    .filter(entry -> location.equals(entry.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            Location nearestAssignedLocation = findCheapestLocation(assignedLocations, vehicle);
            if (nearestAssignedLocation != null) {
                return Arrays.asList(createDestination(nearestAssignedLocation, vehicle.getRechargeOperation()));
            } else {
                return new ArrayList<>();
            }
        }

        String preferredRechargeLocationNames = vehicle.getProperties().get(PROPKEY_PREFERRED_RECHARGE_LOCATION);
        if (preferredRechargeLocationNames != null) {
            List<String> preferredNames = Arrays.asList(preferredRechargeLocationNames.split(","));
            Set<Location> preferredRechargeLocations =
                    preferredNames.stream()
                            .map(name -> pickLocationWithName(name, rechargeLocations.keySet()))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());

            if (!preferredRechargeLocations.isEmpty()) {
                Map<Location, Set<Point>> preferredLocations = rechargeLocations.entrySet()
                        .stream()
                        .filter(entry -> preferredRechargeLocations.contains(entry.getKey()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                Location nearestPreferredLocation = findCheapestLocation(preferredLocations, vehicle);
                return Arrays.asList(createDestination(nearestPreferredLocation, vehicle.getRechargeOperation()));
            }
        }

        Location bestLocation = findCheapestLocation(rechargeLocations, vehicle);
        if (bestLocation != null) {
            return Arrays.asList(createDestination(bestLocation, vehicle.getRechargeOperation()));
        }

        return new ArrayList<>();
    }

    @Nullable
    private Location findCheapestLocation(Map<Location, Set<Point>> locations, Vehicle vehicle) {
        Point curPos = PlantModelService.INSTANCE.getPlantModel().getPoints().get(vehicle.getCurrentPosition());

        return locations.entrySet().stream()
                .map(entry -> bestAccessPointCandidate(vehicle, curPos, entry.getKey(), entry.getValue()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .min(Comparator.comparingLong(candidate -> candidate.costs))
                .map(candidate -> candidate.location)
                .orElse(null);
    }

    private Destination createDestination(Location location, String operation) {
        return new Destination(location.getName(), location.getName(), operation, Collections.emptyMap());
    }

    @Nullable
    private Location pickLocationWithName(String name, Set<Location> locations) {
        return locations.stream()
                .filter(location -> name.equals(location.getName()))
                .findAny()
                .orElse(null);
    }

    /**
     * Finds locations allowing the given operation, and the points they would be accessible from for
     * the given vehicle.
     *
     * @param operation      The operation.
     * @param vehicle        The vehicle.
     * @param targetedPoints The points that are currently targeted by vehicles.
     * @return The locations allowing the given operation, and the points they would be accessible
     * from.
     */
    private Map<Location, Set<Point>> findLocationsForOperation(String operation,
                                                                 Vehicle vehicle,
                                                                 Set<Point> targetedPoints) {
        Map<Location, Set<Point>> result = new HashMap<>();

        for (Location curLoc : PlantModelService.INSTANCE.getPlantModel().getLocations().values()) {
            LocationType lType = PlantModelService.INSTANCE.getPlantModel().getLocationTypes().get(curLoc.getType());
            if (lType.isAllowedOperation(operation)) {
                Set<Point> points = findUnoccupiedAccessPointsForOperation(curLoc,
                        operation,
                        vehicle,
                        targetedPoints);
                if (!points.isEmpty()) {
                    result.put(curLoc, points);
                }
            }
        }

        return result;
    }

    private Set<Point> findUnoccupiedAccessPointsForOperation(Location location,
                                                              String rechargeOp,
                                                              Vehicle vehicle,
                                                              Set<Point> targetedPoints) {
        return location.getAttachedLinks().stream()
                .filter(link -> allowsOperation(link, rechargeOp))
                .map(link -> PlantModelService.INSTANCE.getPlantModel().getPoints().get(link.getPoint()))
                .filter(accessPoint -> isPointUnoccupiedFor(accessPoint, vehicle, targetedPoints))
                .collect(Collectors.toSet());
    }

    /**
     * Checks if the given link either does not define any allowed operations at all (meaning it does
     * not override the allowed operations of the corresponding location's location type), or - if it
     * does - explicitly allows the required recharge operation.
     *
     * @param link      The link to be checked.
     * @param operation The operation to be checked for.
     * @return <code>true</code> if, and only if, the given link does not disallow the given
     * operation.
     */
    private boolean allowsOperation(Link link, String operation) {
        // This link is only interesting if it either does not define any allowed operations (does
        // not override the allowed operations of the corresponding location) at all or, if it does,
        // allows the required recharge operation.
        return link.getAllowedOperations().isEmpty() || link.hasAllowedOperation(operation);
    }

    private Optional<LocationCandidate> bestAccessPointCandidate(Vehicle vehicle,
                                                                 Point srcPosition,
                                                                 Location location,
                                                                 Set<Point> destPositions) {
        return destPositions.stream()
                .map(point -> new LocationCandidate(location,
                        router.getCostsByPointRef(vehicle, srcPosition.getName(), point.getName())))
                .filter(candidate -> candidate.costs < Long.MAX_VALUE)
                .min(Comparator.comparingLong(candidate -> candidate.costs));
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
                .allMatch(point -> !pointOccupiedOrTargetedByOtherVehicle(point, vehicle, targetedPoints));
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

    /**
     * Gathers a set of all points from all blocks that the given point is a member of.
     *
     * @param point The point to check.
     * @return A set of all points from all blocks that the given point is a member of.
     */
    private Set<Point> expandPoints(Point point) {
        return PlantModelService.INSTANCE.expandResources(Collections.singleton(point.getName()))
                .stream()
                .filter(PlantModelService.INSTANCE::isNameOfPoint)
                .map(resource -> PlantModelService.INSTANCE.getPlantModel().getPoints().get(resource))
                .collect(Collectors.toSet());
    }

    private static class LocationCandidate {

        private final Location location;
        private final long costs;

        public LocationCandidate(Location location, long costs) {
            this.location = location;
            this.costs = costs;
        }
    }
}
