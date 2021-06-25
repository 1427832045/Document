package org.opentcs.strategies.basic.dispatching.phase;

import com.seer.srd.route.service.OrderSequenceService;
import com.seer.srd.route.service.PlantModelService;
import com.seer.srd.route.service.TransportOrderService;
import com.seer.srd.route.service.VehicleService;
import org.apache.commons.lang3.StringUtils;
import org.opentcs.components.kernel.Router;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.data.order.OrderSequence;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.strategies.basic.dispatching.AssignmentCandidate;
import org.opentcs.strategies.basic.dispatching.Phase;
import org.opentcs.strategies.basic.dispatching.TransportOrderUtil;
import org.opentcs.strategies.basic.dispatching.selection.candidates.CompositeAssignmentCandidateSelectionFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Assigns vehicles to the next transport orders in their respective order sequences, if any.
 */
public class AssignSequenceSuccessorsPhase implements Phase {

    private static final Logger logger = LoggerFactory.getLogger(AssignSequenceSuccessorsPhase.class);

    private final Router router;

    private final CompositeAssignmentCandidateSelectionFilter assignmentCandidateSelectionFilter;

    private final TransportOrderUtil transportOrderUtil;

    private boolean initialized;

    @Inject
    public AssignSequenceSuccessorsPhase(
            Router router,
            CompositeAssignmentCandidateSelectionFilter assignmentCandidateSelectionFilter,
            TransportOrderUtil transportOrderUtil) {
        this.router = requireNonNull(router, "router");
        this.assignmentCandidateSelectionFilter = requireNonNull(assignmentCandidateSelectionFilter,
                "assignmentCandidateSelectionFilter");
        this.transportOrderUtil = requireNonNull(transportOrderUtil, "transportOrderUtil");
    }

    @Override
    public void initialize() {
        if (isInitialized()) {
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
        if (!isInitialized()) {
            return;
        }
        initialized = false;
    }

    @Override
    public void run() {
        Set<Vehicle> vehicles = VehicleService.INSTANCE.listVehicles().stream()
                .filter(this::readyForNextInSequence)
                .collect(Collectors.toSet());
        for (Vehicle vehicle : vehicles) {
            try {
                tryAssignNextOrderInSequence(vehicle);
            } catch (Exception e) {
                logger.error("tryAssignNextOrderInSequence v=" + vehicle.getName(), e);
            }
        }
    }

    private void tryAssignNextOrderInSequence(Vehicle vehicle) {
        nextOrderInCurrentSequence(vehicle)
                .filter(order -> !transportOrderUtil.hasUnfinishedDependencies(order))
                .map(order -> computeCandidate(vehicle, order))
                .filter(assignmentCandidateSelectionFilter)
                .ifPresent(candidate -> transportOrderUtil.assignTransportOrder(vehicle,
                        candidate.getTransportOrder(),
                        candidate.getDriveOrders()));
    }

    private AssignmentCandidate computeCandidate(Vehicle vehicle, TransportOrder order) {
        return router.getRoute(vehicle, PlantModelService.INSTANCE.getPlantModel()
                .getPoints().get(vehicle.getCurrentPosition()), order)
                .map(driveOrders -> new AssignmentCandidate(vehicle, order, driveOrders))
                .orElse(null);
    }

    private Optional<TransportOrder> nextOrderInCurrentSequence(Vehicle vehicle) {
        String orderSeqName = vehicle.getOrderSequence();
        if (StringUtils.isBlank(orderSeqName)) {
            logger.warn("nextOrderInCurrentSequence but vehicle not order seq. v=" + vehicle.getName());
            return Optional.empty();
        }

        OrderSequence seq = OrderSequenceService.INSTANCE.getSequenceOrNull(orderSeqName);
        if (seq == null) {
            logger.warn("nextOrderInCurrentSequence but no seq " + orderSeqName);
            return Optional.empty();
        }

        // If the order sequence's next order is not available, yet, the vehicle should wait for it.
        String orderName = seq.getNextUnfinishedOrder();
        if (StringUtils.isBlank(orderName)) return Optional.empty();

        // Return the next order to be processed for the sequence.
        TransportOrder order = TransportOrderService.INSTANCE.getOrderOrNull(orderName);
        if (order == null) {
            logger.warn("nextOrderInCurrentSequence but no order " + orderName);
            return Optional.empty();
        }
        return Optional.of(order);
    }

    private boolean readyForNextInSequence(Vehicle vehicle) {
        return vehicle.getIntegrationLevel() == Vehicle.IntegrationLevel.TO_BE_UTILIZED
                && vehicle.getProcState() == Vehicle.ProcState.IDLE
                && vehicle.getState() == Vehicle.State.IDLE
                && vehicle.getCurrentPosition() != null
                && vehicle.getOrderSequence() != null;
    }

}
