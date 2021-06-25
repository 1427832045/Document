package org.opentcs.strategies.basic.dispatching;

import com.seer.srd.model.Path;
import com.seer.srd.model.Point;
import com.seer.srd.route.ReroutingImpossibleStrategy;
import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.model.PlantModel;
import com.seer.srd.route.service.PlantModelService;
import com.seer.srd.route.service.TransportOrderService;
import com.seer.srd.vehicle.Vehicle;
import com.seer.srd.vehicle.driver.VehicleDriverManager;
import org.opentcs.components.kernel.Router;
import org.opentcs.data.order.DriveOrder;
import org.opentcs.data.order.Route;
import org.opentcs.data.order.Step;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.drivers.vehicle.MovementCommand;
import org.opentcs.drivers.vehicle.VehicleController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Provides some utility methods used for rerouting vehicles.
 */
public class RerouteUtil {

    private static final Logger LOG = LoggerFactory.getLogger(RerouteUtil.class);

    private final Router router;

    @Inject
    public RerouteUtil(Router router) {
        this.router = requireNonNull(router, "router");
    }

    public void reroute(Collection<Vehicle> vehicles) {
        for (Vehicle vehicle : vehicles) {
            reroute(vehicle, null);
        }
    }

    public void reroute(Vehicle vehicle, List<Path> exceptPaths) {
        requireNonNull(vehicle, "vehicle");
        LOG.debug("Trying to reroute vehicle '{}' except {}...", vehicle.getName(), exceptPaths);

        if (!vehicle.isProcessingOrder()) {
            LOG.warn("{} can't be rerouted without processing a transport order.", vehicle.getName());
            return;
        }

        TransportOrder originalOrder = TransportOrderService.INSTANCE.getOrderOrNull(vehicle.getTransportOrder());

        Point rerouteSource = getFutureOrCurrentPosition(vehicle);

        // Get all unfinished drive order of the transport order the vehicle is processing
        assert originalOrder != null;
        List<DriveOrder> unfinishedOrders = getUnfinishedDriveOrders(originalOrder);

        // Try to get a new route for the unfinished drive orders from the point
        Optional<List<DriveOrder>> optOrders = tryReroute(unfinishedOrders, vehicle, rerouteSource, exceptPaths);

        // Get the drive order with the new route or stick to the old one
        List<DriveOrder> newDriveOrders;
        if (optOrders.isPresent()) {
            newDriveOrders = optOrders.get();
            LOG.debug("Found a new route for {} from point {}: {}",
                    vehicle.getName(),
                    rerouteSource.getName(),
                    newDriveOrders);
        } else {
            LOG.debug("Couldn't find a new route for {}. Updating the current one...",
                    vehicle.getName());
            unfinishedOrders = updatePathLocks(unfinishedOrders);
            unfinishedOrders
                    = markRestrictedSteps(unfinishedOrders,
                    new ExecutionTest(RouteConfigKt.getRouteConfig().getDispatcher().getReroutingImpossibleStrategy(),
                            rerouteSource));
            newDriveOrders = unfinishedOrders;
        }

        adjustFirstDriveOrder(newDriveOrders, vehicle, originalOrder, rerouteSource);

        LOG.debug("Updating transport order {}...", originalOrder.getName());
        updateTransportOrder(originalOrder, newDriveOrders, vehicle);
    }

    private void adjustFirstDriveOrder(List<DriveOrder> newDriveOrders,
                                       Vehicle vehicle,
                                       TransportOrder originalOrder,
                                       Point rerouteSource) {
        // If the vehicle is currently processing a (drive) order (and not waiting to get the next
        // drive order) we need to take care of it
        if (vehicle.getProcState() == Vehicle.ProcState.PROCESSING_ORDER) {
            if (isPointDestinationOfOrder(rerouteSource, originalOrder.getCurrentDriveOrder())) {
                // The current drive order could not get rerouted, because the vehicle already
                // received all commands for it. Therefore we want to keep the original drive order.
                newDriveOrders.set(0, originalOrder.getCurrentDriveOrder());
            } else {
                // Restore the current drive order's history
                DriveOrder newCurrentOrder = mergeDriveOrders(originalOrder.getCurrentDriveOrder(),
                        newDriveOrders.get(0),
                        vehicle);
                newDriveOrders.set(0, newCurrentOrder);
            }
        }
    }

    private void updateTransportOrder(TransportOrder originalOrder, List<DriveOrder> newDriveOrders, Vehicle vehicle) {
        VehicleController controller = VehicleDriverManager.INSTANCE.getVehicleController(vehicle.getName());

        // Restore the transport order's history
        List<DriveOrder> newOrders = new ArrayList<>();
        newOrders.addAll(originalOrder.getPastDriveOrders());
        newOrders.addAll(newDriveOrders);

        // Update the transport order's drive orders with the re-routed ones
        LOG.debug("{}: Updating drive orders with {}.", originalOrder.getName(), newOrders);
        TransportOrderService.INSTANCE.updateTransportOrderDriveOrders(originalOrder.getName(), newOrders);

        // If the vehicle is currently processing a (drive) order (and not waiting to get the next
        // drive order) we need to take care of it
        if (vehicle.getProcState() == Vehicle.ProcState.PROCESSING_ORDER) {
            // Update the vehicle's current drive order with the new one
            controller.updateDriveOrder(newDriveOrders.get(0), originalOrder.getProperties());
        }

        // Let the router know the vehicle selected another route
        router.selectRoute(vehicle, newOrders);
    }

    /**
     * Returns a list of drive orders that yet haven't been finished for the given transport order.
     *
     * @param order The transport order to get unfinished drive orders from.
     * @return The list of unfinished drive orders.
     */
    public List<DriveOrder> getUnfinishedDriveOrders(TransportOrder order) {
        List<DriveOrder> result = new ArrayList<>();
        result.add(order.getCurrentDriveOrder());
        result.addAll(order.getFutureDriveOrders());
        return result;
    }

    /**
     * Merges the two given drive orders.
     * The merged drive order follows the route of orderA to the point where both drive orders
     * (orderA and orderB) start to diverge. From there, the merged drive order follows the route of
     * orderB.
     *
     * @param orderA  A drive order.
     * @param orderB  A drive order to be merged into {@code orderA}.
     * @param vehicle The vehicle to merge the drive orders for.
     * @return The (new) merged drive order.
     */
    public DriveOrder mergeDriveOrders(DriveOrder orderA, DriveOrder orderB, Vehicle vehicle) {
        // Merge the drive order routes
        Route mergedRoute = mergeRoutes(vehicle, orderA.getRoute(), orderB.getRoute());
        return new DriveOrder(orderA.getDestination(), orderA.getTransportOrder(), mergedRoute,
                orderA.getState());
    }

    /**
     * Tries to re-route the given drive orders.
     *
     * @param driveOrders The drive orders to re-route.
     * @param vehicle     The vehicle to re-route for.
     * @param sourcePoint The source point to re-route from.
     * @return The re-routed list of drive orders, if re-routing is possible, otherwise the original
     * list of drive orders.
     */
    public Optional<List<DriveOrder>> tryReroute(List<DriveOrder> driveOrders, Vehicle vehicle, Point sourcePoint, List<Path> exceptPaths) {
        LOG.debug("Trying to reroute drive orders for {} from {}: {}",
                vehicle.getName(), sourcePoint, driveOrders);
        if (exceptPaths != null && !exceptPaths.isEmpty()) {
            return router.getRoute(vehicle, sourcePoint,
                    TransportOrder.Companion.newWithDriverOrders("reroute-dummy", driveOrders), exceptPaths);
        }
        return router.getRoute(vehicle, sourcePoint,
                TransportOrder.Companion.newWithDriverOrders("reroute-dummy", driveOrders));
    }

    /**
     * Returns the steps the given vehicle will process in the future after processing the commands
     * that have been already sent to it.
     *
     * @param vehicle The vehicle to get the future steps for.
     * @return The steps the given vehicle will process in the future or an empty list, if the given
     * vehicle isn't processing any order.
     */
    public List<Step> getFutureSteps(Vehicle vehicle) {
        String orderName = vehicle.getTransportOrder();
        if (orderName == null) {
            LOG.debug("Vehicle {} isn't processing any order. Can't determine future steps.", vehicle.getName());
            return new ArrayList<>();
        }

        TransportOrder order = TransportOrderService.INSTANCE.getOrderOrNull(orderName);
        VehicleController controller = VehicleDriverManager.INSTANCE.getVehicleController(vehicle.getName());

        // Get the steps for the drive order the vehicle is currently processing
        // The transport order's drive orders and their routes can't be null at this point
        List<Step> currentSteps = order != null
                ? order.getCurrentDriveOrder().getRoute().getSteps() : Collections.emptyList();

        // If movement commands have been sent to the comm adapter, trim the current steps by these.
        // Movement commands may have not been sent to the comm adapter yet, i.e. if needed resources
        // are already allocated by another vehicle.
        if (!controller.getCommandsSent().isEmpty()) {
            List<MovementCommand> commandsSent = new ArrayList<>(controller.getCommandsSent());
            MovementCommand lastCommandSent = commandsSent.get(commandsSent.size() - 1);

            // Trim the current steps / Get the steps that haven't been sent to the comm adapter yet
            currentSteps = currentSteps.subList(
                    currentSteps.indexOf(lastCommandSent.getStep()) + 1, currentSteps.size());
        }

        List<Step> futureSteps = new ArrayList<>();
        futureSteps.addAll(currentSteps);

        // Add the steps from all future drive orders
        order.getFutureDriveOrders().stream()
                .map(driveOrder -> driveOrder.getRoute())
                .map(route -> route.getSteps())
                .forEach(steps -> futureSteps.addAll(steps));

        return futureSteps;
    }

    /**
     * Returns the point the given vehicle will be at after processing all commands that have been
     * currently sent to its comm adapter or its current position, if its sent queue is empty.
     *
     * @param vehicle The vehicle to get the point for.
     * @return The point.
     */
    public Point getFutureOrCurrentPosition(Vehicle vehicle) {
        VehicleController controller = VehicleDriverManager.INSTANCE.getVehicleController(vehicle.getName());
        if (controller.getCommandsSent().isEmpty()) {
            return PlantModelService.INSTANCE.getPlantModel().getPoints().get(vehicle.getCurrentPosition());
        }

        List<MovementCommand> commandsSent = new ArrayList<>(controller.getCommandsSent());
        LOG.debug("Commands sent: {}", commandsSent);
        MovementCommand lastCommandSend = commandsSent.get(commandsSent.size() - 1);
        return PlantModelService.INSTANCE.getPlantModel().getPoints().get(lastCommandSend.getStep().getDestinationPoint());
    }

    /**
     * Checks if the routes of the two given lists of drive orders are equal.
     *
     * @param ordersA A list of drive orders.
     * @param ordersB A list of drive order to be compared with {@code orderA} for equality.
     * @return {@code true} if the rutes are equal to each other and {@code false} otherwise.
     */
    public boolean routesEquals(List<DriveOrder> ordersA, List<DriveOrder> ordersB) {
        List<Route> routesA = ordersA.stream()
                .map(order -> order.getRoute())
                .collect(Collectors.toList());
        List<Route> routesB = ordersB.stream()
                .map(order -> order.getRoute())
                .collect(Collectors.toList());
        return Objects.equals(routesA, routesB);
    }

    private Route mergeRoutes(Vehicle vehicle, Route routeA, Route routeB) {
        // Merge the route steps
        List<Step> mergedSteps = mergeSteps(routeA.getSteps(), routeB.getSteps());

        // Calculate the costs for merged route
        PlantModel pm = PlantModelService.INSTANCE.getPlantModel();
        Point sourcePoint = pm.getPoints().get(mergedSteps.get(0).getSourcePoint());
        Point destinationPoint = pm.getPoints().get(mergedSteps.get(mergedSteps.size() - 1).getDestinationPoint());
        long costs = router.getCosts(vehicle, sourcePoint, destinationPoint);

        return new Route(mergedSteps, costs);
    }

    private List<Step> mergeSteps(List<Step> stepsA, List<Step> stepsB) {
        LOG.debug("Merging steps {} with {}", stepsToPaths(stepsA), stepsToPaths(stepsB));
        List<Step> mergedSteps = new ArrayList<>();

        // Get the step where routeB starts to depart, i.e. the step where routeA and routeB share the
        // same source point
        try {
            Step branchingStep = findStepWithSource(stepsB.get(0).getSourcePoint(), stepsA);
            int branchingIndex = stepsA.indexOf(branchingStep);
            mergedSteps.addAll(stepsA.subList(0, branchingIndex));
        } catch (NullPointerException | NoSuchElementException e) {
            LOG.info("Can not merge steps, select new steps {} for order.", stepsToPaths(stepsB), e);
        }

        mergedSteps.addAll(stepsB);

        // Update the steps route indices since they originate from two different drive orders
        mergedSteps = updateRouteIndices(mergedSteps);

        return mergedSteps;
    }

    private Step findStepWithSource(String sourcePoint, List<Step> steps) {
        LOG.debug("Looking for a step with source point {} in {}", sourcePoint, stepsToPaths(steps));
        return steps.stream()
                .filter(step -> Objects.equals(step.getSourcePoint(), sourcePoint))
                .findFirst()
                .get();
    }

    private List<Step> updateRouteIndices(List<Step> steps) {
        List<Step> updatedSteps = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Step currStep = steps.get(i);
            updatedSteps.add(new Step(currStep.getPath(),
                    currStep.getSourcePoint(),
                    currStep.getDestinationPoint(),
                    currStep.getVehicleOrientation(),
                    i,
                    currStep.getExecutionAllowed()));
        }
        return updatedSteps;
    }

    private List<String> stepsToPaths(List<Step> steps) {
        return steps.stream()
                .map(step -> step.getPath())
                .collect(Collectors.toList());
    }

    private boolean isPointDestinationOfOrder(Point point, DriveOrder order) {
        if (point == null || order == null) {
            return false;
        }
        if (order.getRoute() == null) {
            return false;
        }
        return Objects.equals(point.getName(), order.getRoute().getFinalDestinationPoint());
    }

    private List<DriveOrder> updatePathLocks(List<DriveOrder> orders) {
        List<DriveOrder> updatedOrders = new ArrayList<>();

        for (DriveOrder order : orders) {
            List<Step> updatedSteps = new ArrayList<>();

            for (Step step : order.getRoute().getSteps()) {
                updatedSteps.add(new Step(step.getPath(), step.getSourcePoint(), step.getDestinationPoint(),
                        step.getVehicleOrientation(), step.getRouteIndex(), true));
            }

            Route updatedRoute = new Route(updatedSteps, order.getRoute().getCosts());

            DriveOrder updatedOrder = new DriveOrder(order.getDestination(), order.getTransportOrder(), updatedRoute,
                    order.getState());
            updatedOrders.add(updatedOrder);
        }

        return updatedOrders;
    }

    private List<DriveOrder> markRestrictedSteps(List<DriveOrder> orders,
                                                 Predicate<Step> executionTest) {
        if (RouteConfigKt.getRouteConfig().getDispatcher().getReroutingImpossibleStrategy() == ReroutingImpossibleStrategy.IGNORE_PATH_LOCKS) {
            return orders;
        }
        if (!containsLockedPath(orders)) {
            return orders;
        }

        List<DriveOrder> updatedOrders = new ArrayList<>();
        for (DriveOrder order : orders) {
            List<Step> updatedSteps = new ArrayList<>();

            for (Step step : order.getRoute().getSteps()) {
                boolean executionAllowed = executionTest.test(step);
                LOG.debug("Marking path '{}' allowed: {}", step.getPath(), executionAllowed);
                updatedSteps.add(new Step(step.getPath(),
                        step.getSourcePoint(),
                        step.getDestinationPoint(),
                        step.getVehicleOrientation(),
                        step.getRouteIndex(),
                        executionAllowed));
            }

            Route updatedRoute = new Route(updatedSteps, order.getRoute().getCosts());

            DriveOrder updatedOrder = new DriveOrder(order.getDestination(), order.getTransportOrder(), updatedRoute,
                    order.getState());
            updatedOrders.add(updatedOrder);
        }

        return updatedOrders;
    }

    private boolean containsLockedPath(List<DriveOrder> orders) {
        PlantModel pm = PlantModelService.INSTANCE.getPlantModel();
        return orders.stream()
                .map(order -> order.getRoute().getSteps())
                .flatMap(steps -> steps.stream())
                .anyMatch(step -> {
                    Path path = pm.getPaths().get(step.getPath());
                    return path != null && path.isLocked();
                });
    }

    private class ExecutionTest
            implements Predicate<Step> {

        /**
         * The current fallback strategy.
         */
        private final ReroutingImpossibleStrategy strategy;
        /**
         * The (earliest) point from which execution may not be allowed.
         */
        private final Point source;
        /**
         * Whether execution of a step is allowed.
         */
        private boolean executionAllowed = true;

        /**
         * Creates a new intance.
         *
         * @param strategy The current fallback strategy.
         * @param source   The (earliest) point from which execution may not be allowed.
         */
        public ExecutionTest(ReroutingImpossibleStrategy strategy, Point source) {
            this.strategy = requireNonNull(strategy, "strategy");
            this.source = requireNonNull(source, "source");
        }

        @Override
        public boolean test(Step step) {
            if (!executionAllowed) {
                return false;
            }

            switch (strategy) {
                case PAUSE_IMMEDIATELY:
                    if (Objects.equals(step.getSourcePoint(), source.getName())) {
                        executionAllowed = false;
                    }
                    break;
                case PAUSE_AT_PATH_LOCK: {
                    PlantModel pm = PlantModelService.INSTANCE.getPlantModel();
                    Path path = pm.getPaths().get(step.getPath());
                    if (path != null && path.isLocked()) {
                        executionAllowed = false;
                    }
                    break;
                }
                default:
                    executionAllowed = true;
            }

            return executionAllowed;
        }
    }
}
