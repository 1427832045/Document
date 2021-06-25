package org.opentcs.strategies.basic.dispatching.phase.assignment;

import com.seer.srd.ConfigKt;
import com.seer.srd.route.RerouteTrigger;
import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.service.TransportOrderService;
import com.seer.srd.route.service.VehicleService;
import com.seer.srd.vehicle.driver.VehicleDriverManager;
import org.opentcs.components.kernel.Router;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.data.order.*;
import org.opentcs.strategies.basic.dispatching.Phase;
import org.opentcs.strategies.basic.dispatching.RerouteUtil;
import org.opentcs.strategies.basic.dispatching.TransportOrderUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.inject.Inject;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Assigns the next drive order to each vehicle waiting for it, or finishes the respective transport
 * order if the vehicle has finished its last drive order.
 */
public class AssignNextDriveOrdersPhase implements Phase {

    private static final Logger LOG = LoggerFactory.getLogger(AssignNextDriveOrdersPhase.class);

    private final Router router;

    private final TransportOrderUtil transportOrderUtil;

    private final RerouteUtil rerouteUtil;

    private boolean initialized;

    private Set<String> vehiclesNeedRecover = new HashSet<>();

    private boolean startFromDB = ConfigKt.getCONFIG().getStartFromDB();

    @Inject
    public AssignNextDriveOrdersPhase(Router router,
                                      TransportOrderUtil transportOrderUtil,
                                      RerouteUtil rerouteUtil) {
        this.router = requireNonNull(router, "router");
        this.transportOrderUtil = requireNonNull(transportOrderUtil, "transportOrderUtil");
        this.rerouteUtil = requireNonNull(rerouteUtil, "rerouteUtil");
    }

    @Override
    public void initialize() {
        if (isInitialized()) {
            return;
        }
        initialized = true;
        vehiclesNeedRecover =
                VehicleService.INSTANCE.listVehicles().stream().map(Vehicle::getName).collect(Collectors.toSet());
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
        initialized = false;
    }

    @Override
    public void run() {
        VehicleService.INSTANCE.listVehicles().stream()
                .filter(vehicle -> vehicle.getProcState() == Vehicle.ProcState.AWAITING_ORDER)
                .filter(vehicle -> !hasWithdrawnTransportOrder(vehicle))
                .forEach(this::checkForNextDriveOrder);

        if (startFromDB && !vehiclesNeedRecover.isEmpty()) {
            VehicleService.INSTANCE.listVehicles().stream()
                    .filter(this::needRecover)
                    .forEach(this::checkRecoverDriveOrder);
        }

        if (vehiclesNeedRecover.isEmpty()) {
            TransportOrderService.INSTANCE.setNeedRecover(false);
        }
    }

    private boolean hasWithdrawnTransportOrder(Vehicle vehicle) {
        TransportOrder order = TransportOrderService.INSTANCE.getOrderOrNull(vehicle.getTransportOrder());
        if (order == null) return false;
        return order.hasState(TransportOrderState.WITHDRAWN);
    }

    private boolean needRecover(Vehicle vehicle) {
        return vehiclesNeedRecover.contains(vehicle.getName());
    }

    private void checkRecoverDriveOrder(Vehicle vehicle) {
        LOG.info("Check recovering for vehicle {}.", vehicle.getName());

        if (vehicle.getProcState() != Vehicle.ProcState.PROCESSING_ORDER ||
                vehicle.getTransportOrder() == null ||
                VehicleDriverManager.INSTANCE.getVehicleController(vehicle.getName()).getCurrentDriveOrder() != null) {
            LOG.info("Vehicle {} does not need recovering.", vehicle.getName());
            vehiclesNeedRecover.remove(vehicle.getName());
            return;
        }

        if (vehicle.getCurrentPosition() == null) {
            LOG.info("Vehicle {} need position for recovering.", vehicle.getName());
            return;
        }

        if (vehicle.getState() != Vehicle.State.IDLE) {
            LOG.info("Vehicle {} is not idle, will check to recover later.", vehicle.getName());
            return;
        }

        if (RouteConfigKt.getRouteConfig().getNewCommAdapter() && !vehicle.isDominating()) {
            LOG.info("Vehicle {} is not owned by SRD, will check to recover later.", vehicle.getName());
            return;
        }

        TransportOrder order = TransportOrderService.INSTANCE.getOrderOrNull(vehicle.getTransportOrder());

        if (order == null) {
            LOG.error("Transport order {} is not existed, so can not recover for vehicle {}.",
                    vehicle.getTransportOrder(), vehicle.getName());
            vehiclesNeedRecover.remove(vehicle.getName());
            return;
        }

        DriveOrder currentDriveOrder = order.getCurrentDriveOrder();

        if (currentDriveOrder == null) {
            LOG.error("Drive order is finished, so can not recover for vehicle {}.", vehicle.getName());
            vehiclesNeedRecover.remove(vehicle.getName());
            return;
        }

        if (currentDriveOrder.getState() == DriveOrderState.FINISHED) {
            VehicleService.INSTANCE.updateVehicleProcState(vehicle.getName(), Vehicle.ProcState.AWAITING_ORDER);
            LOG.error("Drive order {} state is {}, change vehicle {} state to AWAITING_ORDER.",
                    currentDriveOrder, currentDriveOrder.getState(), vehicle.getName());
            vehiclesNeedRecover.remove(vehicle.getName());
            return;
        }

        if(currentDriveOrder.getState() == DriveOrderState.PRISTINE) {
            TransportOrderService.INSTANCE.updateCurrentDriveOrderState(order.getName(), DriveOrderState.TRAVELLING);
            LOG.error("Drive order {} state is {}, continue recover for vehicle {}.",
                    currentDriveOrder, currentDriveOrder.getState(), vehicle.getName());
        }

        // 如果机器人还在走的话，清空机器人底层命令
//        LOG.info("Clearing commands for recovering, vehicle {}", vehicle.getName());
//        VehicleDriverManager.INSTANCE.getVehicleController(vehicle.getName()).clearCommandQueue();

        // 还没做到最后一步
        Route currentRoute = currentDriveOrder.getRoute();
        String finalOrderPoint = null;
        if (currentRoute != null) {
            finalOrderPoint = currentRoute.getFinalDestinationPoint();
        } else {
            LOG.error("Current route is null, so can not recover for vehicle {}.", vehicle.getName());
            vehiclesNeedRecover.remove(vehicle.getName());
            return;
        }

        // 如果机器人不在最后一个站点，就 reroute
        if (!vehicle.getCurrentPosition().equals(finalOrderPoint)) {
            LOG.info("Reroute for vehicle {}.", vehicle.getName());
            rerouteUtil.reroute(vehicle, null);
        } else {
            // 做到最后一步，就不再重发最后一步了，等待后面的任务
            LOG.info("Current drive order of vehicle {} is already finished, run next drive order.", vehicle.getName());
            VehicleService.INSTANCE.updateVehicleProcState(vehicle.getName(), Vehicle.ProcState.AWAITING_ORDER);
        }

        vehiclesNeedRecover.remove(vehicle.getName());
    }

    private void checkForNextDriveOrder(Vehicle vehicle) {
        LOG.debug("Vehicle '{}' finished a drive order.", vehicle.getName());
        // The vehicle is processing a transport order and has finished a drive order.
        // See if there's another drive order to be processed.
        TransportOrderService.INSTANCE.updateTransportOrderNextDriveOrder(vehicle.getTransportOrder());
        TransportOrder vehicleOrder = TransportOrderService.INSTANCE.getOrderOrNull(vehicle.getTransportOrder());
        if (vehicleOrder.getCurrentDriveOrder() == null) {
            LOG.debug("Vehicle '{}' finished transport order '{}'",
                    vehicle.getName(),
                    vehicleOrder.getName());
            // The current transport order has been finished - update its state and that of the vehicle.
            transportOrderUtil.updateTransportOrderState(vehicle.getTransportOrder(),
                    TransportOrderState.FINISHED);
            // Update the vehicle's procState, implicitly dispatching it again.
            VehicleService.INSTANCE.updateVehicleProcState(vehicle.getName(), Vehicle.ProcState.IDLE);
            VehicleService.INSTANCE.updateVehicleTransportOrder(vehicle.getName(), null);
            // Let the router know that the vehicle doesn't have a route any more.
            router.selectRoute(vehicle, null);
            // Update transport orders that are dispatchable now that this one has been finished.
            transportOrderUtil.markNewDispatchableOrders();
        } else {
            LOG.debug("Assigning next drive order to vehicle '{}'...", vehicle.getName());
            // Get the next drive order to be processed.
            DriveOrder currentDriveOrder = vehicleOrder.getCurrentDriveOrder();
            if (transportOrderUtil.mustAssign(currentDriveOrder, vehicle)) {
                if (RouteConfigKt.getRouteConfig().getDispatcher().getRerouteTrigger() == RerouteTrigger.DRIVE_ORDER_FINISHED) {
                    LOG.debug("Trying to reroute vehicle '{}' before assigning the next drive order...",
                            vehicle.getName());
                    rerouteUtil.reroute(vehicle, null);
                }

                // Get an up-to-date copy of the transport order in case the route changed
                vehicleOrder = TransportOrderService.INSTANCE.getOrderOrNull(vehicle.getTransportOrder());
                currentDriveOrder = vehicleOrder.getCurrentDriveOrder();

                // Let the vehicle controller know about the new drive order.
                VehicleDriverManager.INSTANCE.getVehicleController(vehicle.getName())
                        .setDriveOrder(currentDriveOrder, vehicleOrder.getProperties());

                // The vehicle is still processing a transport order.
                VehicleService.INSTANCE.updateVehicleProcState(vehicle.getName(), Vehicle.ProcState.PROCESSING_ORDER);
            }
            // If the drive order need not be assigned, immediately check for another one.
            else {
                VehicleService.INSTANCE.updateVehicleProcState(vehicle.getName(), Vehicle.ProcState.AWAITING_ORDER);
                checkForNextDriveOrder(vehicle);
            }
        }
    }
}
