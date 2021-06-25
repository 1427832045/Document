package org.opentcs.strategies.basic.dispatching.phase.recharging;

import com.seer.srd.model.Location;
import com.seer.srd.model.LocationType;
import com.seer.srd.model.Point;
import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.service.PlantModelService;
import com.seer.srd.route.service.TransportOrderService;
import com.seer.srd.route.service.VehicleService;
import org.opentcs.access.to.order.DestinationCreationTO;
import org.opentcs.access.to.order.TransportOrderCreationTO;
import org.opentcs.components.kernel.Router;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.data.order.Destination;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.data.order.TransportOrderState;
import org.opentcs.strategies.basic.dispatching.AssignmentCandidate;
import org.opentcs.strategies.basic.dispatching.Phase;
import org.opentcs.strategies.basic.dispatching.TransportOrderUtil;
import org.opentcs.strategies.basic.dispatching.selection.candidates.CompositeAssignmentCandidateSelectionFilter;
import org.opentcs.strategies.basic.dispatching.selection.vehicles.CompositeRechargeVehicleSelectionFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Creates recharging orders for any vehicles with a degraded energy level.
 */
public class RechargeIdleVehiclesPhase implements Phase {

    private static final Logger LOG = LoggerFactory.getLogger(RechargeIdleVehiclesPhase.class);
    /**
     * The strategy used for finding suitable recharge locations.
     */
    @SuppressWarnings("deprecation")
    private final org.opentcs.components.kernel.RechargePositionSupplier rechargePosSupplier;

    private final Router router;
    /**
     * A collection of predicates for filtering assignment candidates.
     */
    private final CompositeAssignmentCandidateSelectionFilter assignmentCandidateSelectionFilter;

    private final CompositeRechargeVehicleSelectionFilter vehicleSelectionFilter;

    private final TransportOrderUtil transportOrderUtil;

    private boolean initialized;

    @Inject
    @SuppressWarnings("deprecation")
    public RechargeIdleVehiclesPhase(
            org.opentcs.components.kernel.RechargePositionSupplier rechargePosSupplier,
            Router router,
            CompositeAssignmentCandidateSelectionFilter assignmentCandidateSelectionFilter,
            CompositeRechargeVehicleSelectionFilter vehicleSelectionFilter,
            TransportOrderUtil transportOrderUtil) {
        this.router = requireNonNull(router, "router");
        this.rechargePosSupplier = requireNonNull(rechargePosSupplier, "rechargePosSupplier");
        this.assignmentCandidateSelectionFilter = requireNonNull(assignmentCandidateSelectionFilter,
                "assignmentCandidateSelectionFilter");
        this.vehicleSelectionFilter = requireNonNull(vehicleSelectionFilter, "vehicleSelectionFilter");
        this.transportOrderUtil = requireNonNull(transportOrderUtil, "transportOrderUtil");
    }

    @Override
    public void initialize() {
        if (isInitialized()) {
            return;
        }

        rechargePosSupplier.initialize();

        initialized = true;
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

        rechargePosSupplier.terminate();

        initialized = false;
    }

    @Override
    public void run() {
        if (!RouteConfigKt.getRouteConfig().getDispatcher().getRechargeIdleVehicles()) {
            return;
        }

        VehicleService.INSTANCE
            .listVehicles()
            .stream()
            .filter(vehicleSelectionFilter)
            .sorted(Comparator.comparingInt(Vehicle::getEnergyLevel))
            .forEach(this::createRechargeOrder);
    }

    private void createRechargeOrder(Vehicle vehicle) {
        List<Destination> rechargeDests = rechargePosSupplier.findRechargeSequence(vehicle);
        LOG.debug("Recharge sequence for {}: {}", vehicle, rechargeDests);

        if (rechargeDests.isEmpty()) {
            LOG.info("{}: Did not find a suitable recharge sequence.", vehicle.getName());
            return;
        }

        List<DestinationCreationTO> chargeDests = new ArrayList<>(rechargeDests.size());
        for (Destination dest : rechargeDests) {
            // 如果两段充电的开关打开
            if (RouteConfigKt.getRouteConfig().getDispatcher().getTwoStageRecharge()) {
                LOG.info("Two stage recharge order for vehicle {}", vehicle.getName());
                // 获取充电的前置 location
                Location preChargeLocation = PlantModelService.INSTANCE
                        .getPlantModel()
                        .getLocations()
                        .getOrDefault("PRE-" + dest.getDestination(), null);
                if (preChargeLocation != null) {
                    // 获取充电前置 Location 的 Type
                    LocationType preChargeType = PlantModelService.INSTANCE
                            .getPlantModel()
                            .getLocationTypes()
                            .get(preChargeLocation.getType());
                    // 充电前置 Location 需要支持 Wait 操作
                    if (preChargeType.isAllowedOperation("Wait")) {
                        LOG.info("Create pre-recharge destination for vehicle {}", vehicle.getName());
                        chargeDests.add(new DestinationCreationTO(preChargeLocation.getName(), "Wait"));
                    } else {
                        LOG.error("{} does not support the key operation 'Wait'.", preChargeLocation.getName());
                    }
                } else {
                    LOG.info("{} does not have a PRE Location for two stage recharge.", dest.getDestination());
                }
            }
            chargeDests.add(
                    new DestinationCreationTO(dest.getDestination(), dest.getOperation())
                            .withProperties(dest.getProperties())
            );
        }
        // Create a transport order for recharging and verify its processability.
        // The recharge order may be withdrawn unless its energy level is critical.
        TransportOrder rechargeOrder = TransportOrderService.INSTANCE.createTransportOrder(
                new TransportOrderCreationTO("Recharge-" + UUID.randomUUID(), chargeDests)
                        .withIntendedVehicleName(vehicle.getName())
                        .withDispensable(!vehicle.isEnergyLevelCritical())
        );

        Point vehiclePosition = PlantModelService.INSTANCE.getPlantModel().getPoints().get(vehicle.getCurrentPosition());
        Optional<AssignmentCandidate> candidate = computeCandidate(vehicle, vehiclePosition, rechargeOrder)
                .filter(assignmentCandidateSelectionFilter);
        // XXX Change this to Optional.ifPresentOrElse() once we're at Java 9+.
        if (candidate.isPresent()) {
            transportOrderUtil.assignTransportOrder(candidate.get().getVehicle(),
                    candidate.get().getTransportOrder(),
                    candidate.get().getDriveOrders());
        } else {
            // Mark the order as failed, since the vehicle cannot execute it.
            TransportOrderService.INSTANCE.updateTransportOrderState(rechargeOrder.getName(), TransportOrderState.FAILED);
        }
    }

    private Optional<AssignmentCandidate> computeCandidate(Vehicle vehicle,
                                                           Point vehiclePosition,
                                                           TransportOrder order) {
        return router.getRoute(vehicle, vehiclePosition, order)
                .map(driveOrders -> new AssignmentCandidate(vehicle, order, driveOrders));
    }
}
