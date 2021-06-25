package org.opentcs.strategies.basic.routing;

import com.seer.srd.model.*;
import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.model.PlantModel;
import com.seer.srd.route.service.PlantModelService;
import com.seer.srd.route.service.VehicleService;
import org.opentcs.components.kernel.Router;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.data.order.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static org.opentcs.strategies.basic.routing.PointRouter.INFINITE_COSTS;

/**
 * A basic {@link Router} implementation.
 */
public class DefaultRouter implements Router {

    /**
     * The default value of a vehicle's routing group.
     */
    private static final String DEFAULT_ROUTING_GROUP = "";

    private static final Logger LOG = LoggerFactory.getLogger(DefaultRouter.class);

    /**
     * A builder for constructing our routing tables.
     */
    private final PointRouterFactory pointRouterFactory;
    /**
     * The routes selected for each vehicle.
     */
    private final Map<Vehicle, List<DriveOrder>> routesByVehicle = new ConcurrentHashMap<>();
    /**
     * The point routers by vehicle routing group.
     */
    private final Map<String, PointRouter> pointRoutersByVehicleGroup = new ConcurrentHashMap<>();
    /**
     * Prevents reading from the routing tables and planned routes while updating them.
     */
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    private boolean initialized;

    @Inject
    public DefaultRouter(PointRouterFactory pointRouterFactory) {
        this.pointRouterFactory = requireNonNull(pointRouterFactory, "pointRouterFactory");
    }

    @Override
    public void initialize() {
        if (isInitialized()) {
            return;
        }
        try {
            rwLock.writeLock().lock();
            routesByVehicle.clear();
            updateRoutingTables();
            initialized = true;
        } finally {
            rwLock.writeLock().unlock();
        }
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
        try {
            rwLock.writeLock().lock();
            routesByVehicle.clear();
            pointRoutersByVehicleGroup.clear();
            initialized = false;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    @Deprecated
    public void updateRoutingTables() {
        try {
            rwLock.writeLock().lock();
            pointRoutersByVehicleGroup.clear();
            for (Vehicle curVehicle : VehicleService.INSTANCE.listVehicles()) {
                String currentGroup = getRoutingGroupOfVehicle(curVehicle);
                if (!pointRoutersByVehicleGroup.containsKey(currentGroup)) {
                    pointRoutersByVehicleGroup.put(currentGroup,
                            pointRouterFactory.createPointRouter(curVehicle));
                }
            }
            LOG.debug("Number of point routers created: {}", pointRoutersByVehicleGroup.size());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public Set<Vehicle> checkRoutability(TransportOrder order) {
        requireNonNull(order, "order");
        try {
            rwLock.readLock().lock();
            Set<Vehicle> result = new HashSet<>();
            List<DriveOrder> driveOrderList = order.getFutureDriveOrders();
            DriveOrder[] driveOrders = driveOrderList.toArray(new DriveOrder[driveOrderList.size()]);
            for (Map.Entry<String, PointRouter> curEntry : pointRoutersByVehicleGroup.entrySet()) {
                // Get all points at the first location at which a vehicle of the current
                // type can execute the desired operation and check if an acceptable route
                // originating in one of them exists.
                for (Point curStartPoint : getDestinationPoints(driveOrders[0])) {
                    if (isRoutable(curStartPoint, driveOrders, 1, curEntry.getValue())) {
                        result.addAll(getVehiclesByRoutingGroup(curEntry.getKey()));
                        break;
                    }
                }
            }
            return result;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public Optional<List<DriveOrder>> getRoute(Vehicle vehicle, Point sourcePoint, TransportOrder transportOrder) {
        requireNonNull(vehicle, "vehicle");
        requireNonNull(sourcePoint, "sourcePoint");
        requireNonNull(transportOrder, "transportOrder");

        try {
            rwLock.readLock().lock();
            List<DriveOrder> driveOrderList = transportOrder.getFutureDriveOrders();
            DriveOrder[] driveOrders = driveOrderList.toArray(new DriveOrder[driveOrderList.size()]);
            PointRouter pointRouter = pointRoutersByVehicleGroup.get(getRoutingGroupOfVehicle(vehicle));
            OrderRouteParameterStruct params = new OrderRouteParameterStruct(driveOrders, pointRouter);
            OrderRouteResultStruct resultStruct = new OrderRouteResultStruct(driveOrderList.size());
            computeCheapestOrderRoute(sourcePoint, params, 0, resultStruct);
            return (resultStruct.bestCosts == Long.MAX_VALUE)
                    ? Optional.empty()
                    : Optional.of(Arrays.asList(resultStruct.bestRoute));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public Optional<List<DriveOrder>> getRoute(Vehicle vehicle, Point sourcePoint, TransportOrder transportOrder, List<Path> exceptPaths) {
        requireNonNull(vehicle, "vehicle");
        requireNonNull(sourcePoint, "sourcePoint");
        requireNonNull(transportOrder, "transportOrder");

        try {
            rwLock.readLock().lock();
            List<DriveOrder> driveOrderList = transportOrder.getFutureDriveOrders();
            DriveOrder[] driveOrders = driveOrderList.toArray(new DriveOrder[driveOrderList.size()]);
            PointRouter pointRouter = pointRouterFactory.createPointRouter(vehicle, exceptPaths);
            OrderRouteParameterStruct params = new OrderRouteParameterStruct(driveOrders, pointRouter);
            OrderRouteResultStruct resultStruct = new OrderRouteResultStruct(driveOrderList.size());
            computeCheapestOrderRoute(sourcePoint, params, 0, resultStruct);
            return (resultStruct.bestCosts == Long.MAX_VALUE)
                    ? Optional.empty()
                    : Optional.of(Arrays.asList(resultStruct.bestRoute));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public Optional<Route> getRoute(Vehicle vehicle,
                                    Point sourcePoint,
                                    Point destinationPoint) {
        requireNonNull(vehicle, "vehicle");
        requireNonNull(sourcePoint, "sourcePoint");
        requireNonNull(destinationPoint, "destinationPoint");

        try {
            rwLock.readLock().lock();
            PointRouter pointRouter = pointRoutersByVehicleGroup.get(getRoutingGroupOfVehicle(vehicle));
            long costs = pointRouter.getCosts(sourcePoint, destinationPoint);
            if (costs == INFINITE_COSTS) {
                return Optional.empty();
            }
            List<Step> steps = pointRouter.getRouteSteps(sourcePoint, destinationPoint);
            if (steps.isEmpty()) {
                // If the list of steps is empty, we're already at the destination point
                // Create a single step without a path.
                steps.add(new Step(null, null, sourcePoint.getName(), Vehicle.Orientation.UNDEFINED, 0, true));
            }
            return Optional.of(new Route(steps, costs));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public long getCosts(Vehicle vehicle,
                         Point sourcePoint,
                         Point destinationPoint) {
        requireNonNull(vehicle, "vehicle");
        requireNonNull(sourcePoint, "sourcePoint");
        requireNonNull(destinationPoint, "destinationPoint");

        try {
            rwLock.readLock().lock();
            return pointRoutersByVehicleGroup.get(getRoutingGroupOfVehicle(vehicle))
                    .getCosts(sourcePoint, destinationPoint);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public long getCostsByPointRef(Vehicle vehicle, String srcPointName, String dstPointName) {
        requireNonNull(vehicle, "vehicle");
        requireNonNull(srcPointName, "srcPointName");
        requireNonNull(dstPointName, "dstPointName");

        try {
            rwLock.readLock().lock();
            return pointRoutersByVehicleGroup.get(getRoutingGroupOfVehicle(vehicle))
                    .getCosts(srcPointName, dstPointName);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    @Deprecated
    public long getCosts(Vehicle vehicle, String srcLocName, String destLocName) {
        requireNonNull(vehicle, "vehicle");
        requireNonNull(srcLocName, "srcLocName");
        requireNonNull(destLocName, "destLocName");

        try {
            rwLock.readLock().lock();
            // Get all attached links for source and destination
            PlantModel pm = PlantModelService.INSTANCE.getPlantModel();
            Set<Link> srcLinks = pm.getLocations().get(srcLocName).getAttachedLinks();
            Set<Link> destLinks = pm.getLocations().get(destLocName).getAttachedLinks();

            // Find the cheapest destination link to be used
            long costs = Long.MAX_VALUE;
            for (Link srcLink : srcLinks) {
                for (Link destLink : destLinks) {
                    long linkCosts = getCosts(vehicle,
                            pm.getPoints().get(srcLink.getPoint()), pm.getPoints().get(destLink.getPoint()));
                    costs = Math.min(costs, linkCosts);
                }
            }
            return costs;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public void selectRoute(Vehicle vehicle, List<DriveOrder> driveOrders) {
        requireNonNull(vehicle, "vehicle");

        try {
            rwLock.writeLock().lock();
            if (driveOrders == null) {
                // XXX Should we remember the vehicle's current position, maybe?
                routesByVehicle.remove(vehicle);
            } else {
                routesByVehicle.put(vehicle, driveOrders);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public Map<Vehicle, List<DriveOrder>> getSelectedRoutes() {
        try {
            rwLock.readLock().lock();
            return new HashMap<>(routesByVehicle);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public Set<Point> getTargetedPoints() {
        try {
            rwLock.readLock().lock();
            Set<String> result = new HashSet<>();
            for (List<DriveOrder> curOrderList : routesByVehicle.values()) {
                DriveOrder finalOrder = curOrderList.get(curOrderList.size() - 1);
                result.add(finalOrder.getRoute().getFinalDestinationPoint());
            }
            PlantModel pm = PlantModelService.INSTANCE.getPlantModel();
            return result.stream().map(pn -> pm.getPoints().get(pn)).collect(Collectors.toSet());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Checks if a route exists for a vehicle of a given type which allows the
     * vehicle to process a given list of drive orders.
     *
     * @param startPoint   The point at which the route is supposed to start.
     * @param driveOrders  The list of drive orders, in the order they are to be
     *                     processed.
     * @param nextHopIndex The index of the next drive order in the list.
     * @param pointRouter  The point router to use.
     * @return <code>true</code> if, and only if, at least one route exists which
     * would allow a vehicle of the given type to process the whole list of drive
     * orders.
     */
    private boolean isRoutable(Point startPoint,
                               DriveOrder[] driveOrders,
                               int nextHopIndex,
                               PointRouter pointRouter) {
        assert startPoint != null;
        assert driveOrders != null;
        assert pointRouter != null;

        if (nextHopIndex < driveOrders.length) {
            for (Point curPoint : getDestinationPoints(driveOrders[nextHopIndex])) {
                // Check if there is a route from the starting point to the current
                // point and if the rest of the orders are routable from there, too.
                if (pointRouter.getCosts(startPoint, curPoint) != INFINITE_COSTS
                        && isRoutable(curPoint, driveOrders, nextHopIndex + 1, pointRouter)) {
                    // If it was possible to reach the end of the order list from here,
                    // propagate the result back to the caller.
                    return true;
                }
            }
            // If we haven't found an acceptable route, return false.
            return false;
        }
        // If we have reached the end of the list, it seems we have found a route.
        else {
            return true;
        }
    }

    /**
     * Compute the cheapest route along a list of drive orders/checkpoints.
     *
     * @param startPoint The current checkpoint which to start at.
     * @param params     A struct describing parameters for the route to be computed.
     * @param hopIndex   The current index in the list of drive orders/checkpoints.
     * @param result     A struct for keeping the (partial) result in.
     */
    private void computeCheapestOrderRoute(Point startPoint,
                                           OrderRouteParameterStruct params,
                                           int hopIndex,
                                           OrderRouteResultStruct result) {
        assert startPoint != null;
        assert params != null;
        assert result != null;
        // If we haven't reached the final drive order in the list, yet...
        if (hopIndex < params.driveOrders.length) {
            // ...try every possible destination point of the current drive order as
            // the next checkpoint and recursively route from there.
            final long currentRouteCosts = result.currentCosts;
            Set<Point> destPoints = getDestinationPoints(params.driveOrders[hopIndex]);
            // If the set of destination points contains the starting point, keep only
            // that one. This is just a shortcut - it is the cheapest way to go.
            if (!RouteConfigKt.getRouteConfig().getRouteToCurrentPosition() && destPoints.contains(startPoint)) {
                LOG.debug("Shortcutting route to {}", startPoint);
                destPoints.clear();
                destPoints.add(startPoint);
            }
            boolean routable = false;
            for (Point curDestPoint : destPoints) {
                final long hopCosts = params.pointRouter.getCosts(startPoint, curDestPoint);
                if (hopCosts == INFINITE_COSTS) {
                    continue;
                }
                // Get the list of steps for the route of the current drive order.
                List<Step> steps = params.pointRouter.getRouteSteps(startPoint, curDestPoint);
                if (steps.isEmpty()) {
                    // If the list of steps returned is empty, we're already at the
                    // destination point of the drive order - create a single step
                    // without a path.
                    steps = new ArrayList<>(1);
                    steps.add(new Step(null, null, startPoint.getName(), Vehicle.Orientation.UNDEFINED, 0, true));
                }
                // Create a route from the list of steps gathered.
                Route hopRoute = new Route(steps, hopCosts);
                // Copy the current drive order, add the computed route to it and
                // place it in the result struct.
                DriveOrder old = params.driveOrders[hopIndex];
                DriveOrder hopOrder = new DriveOrder(old.getDestination(), old.getTransportOrder(), hopRoute, old.getState());
                result.currentRoute[hopIndex] = hopOrder;
                // Calculate the costs for the route so far, too.
                result.currentCosts = currentRouteCosts + hopRoute.getCosts();
                computeCheapestOrderRoute(curDestPoint, params, hopIndex + 1, result);
                // Remember that we did find at least one route that works.
                routable = true;
            }
            if (!routable) {
                // Setting currentCosts is not strictly necessary for this algorithm,
                // but might help with debugging.
                result.currentCosts = Long.MAX_VALUE;
            }
        }
        // If we have reached the final drive order, ...
        else // If the route computed is cheaper than the best route found so far,
            // replace the latter.
            if (result.currentCosts < result.bestCosts) {
                System.arraycopy(result.currentRoute, 0, result.bestRoute, 0, result.currentRoute.length);
                result.bestCosts = result.currentCosts;
            }
    }

    /**
     * Returns all points at which a vehicle could process the given drive order.
     *
     * @param driveOrder The drive order to be processed.
     * @return A set of acceptable destination points at which a vehicle could
     * execute the given drive order's operation. If no such points exist, the
     * returned set will be empty.
     */
    private Set<Point> getDestinationPoints(DriveOrder driveOrder) {
        assert driveOrder != null;
        final Destination dest = driveOrder.getDestination();
        // If the destination references a point and the operation is "just move" or
        // "park the vehicle", this is an order to send the vehicle to an explicitly
        // selected point - return an appropriate set with only that point.
        PlantModel pm = PlantModelService.INSTANCE.getPlantModel();
        if (PlantModelService.INSTANCE.isNameOfPoint(dest.getDestination())
                && (Destination.OP_MOVE.equals(dest.getOperation())
                || Destination.OP_PARK.equals(dest.getOperation()))) {
            // Route the vehicle to an user selected point if halting is allowed there.
            Point destPoint = pm.getPoints().get(dest.getDestination());
            requireNonNull(destPoint, "destPoint");
            final Set<Point> result = new HashSet<>();
            if (destPoint.isHaltingPosition()) {
                result.add(destPoint);
            }
            return result;
        }
        // If it's a "normal" transport order, look for destination points adjacent
        // to the destination location.
        else {
            final Set<Point> result = new HashSet<>();
            final Location destLoc = pm.getLocations().get(dest.getDestination());
            final LocationType destLocType = pm.getLocationTypes().get(destLoc.getType());
            for (Link curLink : destLoc.getAttachedLinks()) {
                // A link is acceptable if any of the following conditions are true:
                // - The destination operation is OP_NOP, which is allowed everywhere.
                // - The destination operation is explicitly allowed with the link.
                // - The link's set of allowed operations is empty and the destination
                //   operation is explicitly allowed with the location's type.
                // Furthermore, the point to be routed at must allow halting.
                if (Destination.OP_NOP.equals(dest.getOperation())
                        || curLink.hasAllowedOperation(dest.getOperation())
                        || (curLink.getAllowedOperations().isEmpty()
                        && destLocType.isAllowedOperation(dest.getOperation()))) {
                    Point destPoint = pm.getPoints().get(curLink.getPoint());
                    if (destPoint.isHaltingPosition()) {
                        result.add(destPoint);
                    }
                }
            }
            return result;
        }
    }

    /**
     * Returns all vehicles within the given routing group.
     *
     * @param routingGroup The routing group the returned vehicles should belong to.
     * @return The vehicles which have the given routing group
     */
    private Set<Vehicle> getVehiclesByRoutingGroup(String routingGroup) {
        Set<Vehicle> result = new HashSet<>();
        for (Vehicle curVehicle : VehicleService.INSTANCE.listVehicles()) {
            if (Objects.equals(getRoutingGroupOfVehicle(curVehicle), routingGroup)) {
                result.add(curVehicle);
            }
        }
        return result;
    }

    /**
     * Returns the routing group of the vehicle or {@link #DEFAULT_ROUTING_GROUP} if the property
     * does not exist or is invalid.
     *
     * @param vehicle The vehicle
     * @return The routing group of the vehicle
     */
    private String getRoutingGroupOfVehicle(Vehicle vehicle) {
        String propVal = vehicle.getProperties().get(PROPKEY_ROUTING_GROUP);

        return propVal == null ? DEFAULT_ROUTING_GROUP : propVal;
    }

    /**
     * Contains parameters for a route to be computed.
     */
    private static final class OrderRouteParameterStruct {

        /**
         * The drive orders containing the route's checkpoints.
         */
        private final DriveOrder[] driveOrders;
        /**
         * The point router for the vehicle type.
         */
        private final PointRouter pointRouter;

        /**
         * Creates a new OrderRouteParameterStruct.
         *
         * @param driveOrders A list of drive orders to be processed as checkpoints
         *                    of the route to be computed.
         * @param pointRouter The point router for the vehicle type.
         */
        public OrderRouteParameterStruct(DriveOrder[] driveOrders,
                                         PointRouter pointRouter) {
            this.driveOrders = requireNonNull(driveOrders, "driveOrders");
            this.pointRouter = requireNonNull(pointRouter, "pointRouter");
        }
    }

    /**
     * A struct supporting cheapest route calculation.
     */
    private static final class OrderRouteResultStruct {

        /**
         * The (possibly partial) route currently being examined.
         */
        private DriveOrder[] currentRoute;
        /**
         * The costs of the route currently being examined.
         */
        private long currentCosts;
        /**
         * The best route found so far.
         */
        private DriveOrder[] bestRoute;
        /**
         * The costs of the best route found so far.
         */
        private long bestCosts;

        /**
         * Creates a new OrderRouteResultStruct.
         *
         * @param driveOrderCount The number of <code>DriveOrder</code>s in the
         *                        <code>TransportOrder</code> for which this struct is to store the
         *                        routing result.
         */
        public OrderRouteResultStruct(int driveOrderCount) {
            currentRoute = new DriveOrder[driveOrderCount];
            currentCosts = 0;
            bestRoute = new DriveOrder[driveOrderCount];
            bestCosts = Long.MAX_VALUE;
        }
    }
}
