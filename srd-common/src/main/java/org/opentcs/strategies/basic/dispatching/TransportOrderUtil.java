package org.opentcs.strategies.basic.dispatching;

import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.service.OrderSequenceService;
import com.seer.srd.route.service.TransportOrderService;
import com.seer.srd.route.service.VehicleService;
import com.seer.srd.vehicle.Vehicle;
import com.seer.srd.vehicle.driver.VehicleDriverManager;
import org.opentcs.components.Lifecycle;
import org.opentcs.components.kernel.Router;
import org.opentcs.data.ObjectUnknownException;
import org.opentcs.data.order.*;
import org.opentcs.drivers.vehicle.VehicleController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/**
 * Provides service functions for working with transport orders and their states.
 */
public class TransportOrderUtil implements Lifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(TransportOrderUtil.class);

    private final Router router;

    private final OrderReservationPool orderReservationPool;
    /**
     * A list of vehicles that are to be disabled/made UNAVAILABLE after they have
     * finished/aborted their current transport orders.
     */
    private final Set<String> vehiclesToDisable = ConcurrentHashMap.newKeySet();

    private boolean initialized;

    @Inject
    public TransportOrderUtil(@Nonnull Router router,
                              @Nonnull OrderReservationPool orderReservationPool) {
        this.router = requireNonNull(router, "router");
        this.orderReservationPool = requireNonNull(orderReservationPool, "orderReservationPool");
    }

    @Override
    public void initialize() {
        if (isInitialized()) {
            return;
        }

        vehiclesToDisable.clear();

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

    /**
     * Checks if a transport order's dependencies are completely satisfied or not.
     *
     * @param order A reference to the transport order to be checked.
     * @return <code>false</code> if all the order's dependencies are finished (or
     * don't exist any more), else <code>true</code>.
     */
    public boolean hasUnfinishedDependencies(TransportOrder order) {
        requireNonNull(order, "order");

        // Assume that FINISHED orders do not have unfinished dependencies.
        if (order.hasState(TransportOrderState.FINISHED)) {
            return false;
        }
        // Check if any transport order referenced as a an explicit dependency
        // (really still exists and) is not finished.
        if (order.getDependencies().stream()
                .map(TransportOrderService.INSTANCE::getOrderOrNull)
                .anyMatch(dep -> dep != null && !dep.hasState(TransportOrderState.FINISHED))) {
            return true;
        }

        // Check if the transport order is part of an order sequence and if yes,
        // if it's the next unfinished order in the sequence.
        if (order.getWrappingSequence() != null) {
            OrderSequence seq = OrderSequenceService.INSTANCE.getSequenceOrNull(order.getWrappingSequence());
            if (seq == null) return false;
            String seqName = seq.getName();
            return !order.getName().equals(getNextUnfinishedOrderFromSequence(seqName));
        }
        // All referenced transport orders either don't exist (any more) or have
        // been finished already.
        return false;
    }

    /**
     * Finds transport orders that are ACTIVE and do not have any unfinished dependencies (any more),
     * marking them as DISPATCHABLE.
     */
    public void markNewDispatchableOrders() {
        TransportOrderService.INSTANCE.listUnfinishedOrders().stream()
                .filter(order -> order.hasState(TransportOrderState.ACTIVE))
                .filter(order -> !hasUnfinishedDependencies(order))
                .forEach(order -> updateTransportOrderState(order.getName(),
                        TransportOrderState.DISPATCHABLE));
    }

    public void updateTransportOrderState(@Nonnull String orderName, @Nonnull TransportOrderState newState) {
        requireNonNull(orderName, "orderName");
        requireNonNull(newState, "newState");

        LOG.info("Updating state of transport order {} to {}...", orderName, newState);
        switch (newState) {
            case FINISHED:
                setTOStateFinished(orderName);
                break;
            case FAILED:
                setTOStateFailed(orderName);
                break;
            default:
                // Set the transport order's state.
                TransportOrderService.INSTANCE.updateTransportOrderState(orderName, newState);
        }
    }

    /**
     * Assigns a transport order to a vehicle, stores a route for the vehicle in
     * the transport order, adjusts the state of vehicle and transport order
     * and starts processing.
     *
     * @param vehicle        The vehicle that is supposed to process the transport order.
     * @param transportOrder The transport order to be processed.
     * @param driveOrders    The list of drive orders describing the route for the vehicle.
     */
    public void assignTransportOrder(Vehicle vehicle,
                                     TransportOrder transportOrder,
                                     List<DriveOrder> driveOrders) {
        requireNonNull(vehicle, "vehicle");
        requireNonNull(transportOrder, "transportOrder");
        requireNonNull(driveOrders, "driveOrders");

        LOG.debug("Assigning vehicle {} to order {}.", vehicle.getName(), transportOrder.getName());
        // If the transport order was reserved, forget the reservation now.
        orderReservationPool.removeReservation(transportOrder.getName());
        // Set the vehicle's and transport order's state.
        VehicleService.INSTANCE.updateVehicleProcState(vehicle.getName(), Vehicle.ProcState.PROCESSING_ORDER);
        updateTransportOrderState(transportOrder.getName(), TransportOrderState.BEING_PROCESSED);
        // Add cross references between vehicle and transport order/order sequence.
        VehicleService.INSTANCE.updateVehicleTransportOrder(vehicle.getName(), transportOrder.getName());
        if (transportOrder.getWrappingSequence() != null) {
            VehicleService.INSTANCE.updateVehicleOrderSequence(vehicle.getName(), transportOrder.getWrappingSequence());
            OrderSequenceService.INSTANCE
                    .updateOrderSequenceProcessingVehicle(transportOrder.getWrappingSequence(), vehicle.getName());
        }
        TransportOrderService.INSTANCE.updateProcessingVehicle(transportOrder.getName(), vehicle.getName(), driveOrders);
        // Let the router know about the route chosen.
        router.selectRoute(vehicle, Collections.unmodifiableList(driveOrders));
        // Update the transport order's copy.
        TransportOrder updatedOrder = TransportOrderService.INSTANCE.getOrderOrNull(transportOrder.getName());
        DriveOrder driveOrder = updatedOrder.getCurrentDriveOrder();
        // If the drive order must be assigned, do so.
        if (mustAssign(driveOrder, vehicle)) {
            // Let the vehicle controller know about the first drive order.
            VehicleDriverManager.INSTANCE.getVehicleController(vehicle.getName())
                    .setDriveOrder(driveOrder, updatedOrder.getProperties());
        }
        // If the drive order need not be assigned, let the kernel know that the
        // vehicle is waiting for its next order - it will be dispatched again for
        // the next drive order, then.
        else {
            VehicleService.INSTANCE.updateVehicleProcState(vehicle.getName(), Vehicle.ProcState.AWAITING_ORDER);
        }
    } // void assignTransportOrder()

    /**
     * Checks if the given drive order must be processed or could/should be left out.
     * Orders that should be left out are those with destinations at which the
     * vehicle is already present and which require no destination operation.
     *
     * @param driveOrder The drive order to be processed.
     * @param vehicle    The vehicle that would process the order.
     * @return <code>true</code> if, and only if, the given drive order must be
     * processed; <code>false</code> if the order should/must be left out.
     */
    public boolean mustAssign(DriveOrder driveOrder, Vehicle vehicle) {
        requireNonNull(vehicle, "vehicle");
        // Removing a vehicle's drive order is always allowed.
        if (driveOrder == null) {
            return true;
        }
        // Check if all orders are to be assigned.
        if (RouteConfigKt.getRouteConfig().getDispatcher().getAssignRedundantOrders()) {
            return true;
        }
        String destPoint = driveOrder.getRoute().getFinalDestinationPoint();
        String destOp = driveOrder.getDestination().getOperation();
        // We use startsWith(OP_NOP) here because that makes it possible to have
        // multiple different operations ("NOP.*") that all do nothing.
        return !destPoint.equals(vehicle.getCurrentPosition())
                || (!destOp.startsWith(Destination.OP_NOP)
                && !destOp.equals(Destination.OP_MOVE));
    }

    public void finishAbortion(Vehicle vehicle) {
        finishAbortion(vehicle.getTransportOrder(), vehicle, vehiclesToDisable.contains(vehicle.getName()));
    }

    private void finishAbortion(String orderName, Vehicle vehicle, boolean disableVehicle) {
        requireNonNull(orderName, "orderName");
        requireNonNull(vehicle, "vehicle");

        LOG.debug("{}: Aborted order {} finished", vehicle.getName(), orderName);

        // The current transport order has been aborted - update its state
        // and that of the vehicle.
        updateTransportOrderState(orderName, TransportOrderState.FAILED);
        // Check if we're supposed to disable the vehicle and set its proc
        // state accordingly.
        if (disableVehicle) {
            VehicleService.INSTANCE.updateVehicleProcState(vehicle.getName(), Vehicle.ProcState.UNAVAILABLE);
            VehicleService.INSTANCE.updateVehicleIntegrationLevel(vehicle.getName(),
                    Vehicle.IntegrationLevel.TO_BE_RESPECTED);
            vehiclesToDisable.remove(vehicle.getName());
        } else {
            VehicleService.INSTANCE.updateVehicleProcState(vehicle.getName(), Vehicle.ProcState.IDLE);
        }
        VehicleService.INSTANCE.updateVehicleTransportOrder(vehicle.getName(), null);
        // Let the router know that the vehicle doesn't have a route any more.
        router.selectRoute(vehicle, null);
    }

    /**
     * Let a given vehicle abort any order it may currently be processing.
     *
     * @param vehicle        The vehicle which should abort its order.
     * @param immediateAbort Whether to abort the order immediately instead of
     *                       just withdrawing it for a smooth abortion.
     * @param disableVehicle Whether to disable the vehicle, i.e. set its
     *                       procState to UNAVAILABLE.
     */
    public void abortOrder(Vehicle vehicle,
                           boolean immediateAbort,
                           boolean disableVehicle,
                           boolean resetVehiclePosition) {

        String orderRef = VehicleService.INSTANCE.getVehicle(vehicle.getName()).getTransportOrder();

        // If the vehicle does NOT have an order, update its processing state now.
        if (orderRef == null) {
            LOG.debug("{}: withdrawal order but order is null", vehicle.getName());
            if (disableVehicle) {
                VehicleService.INSTANCE.updateVehicleProcState(vehicle.getName(), Vehicle.ProcState.UNAVAILABLE);
                VehicleService.INSTANCE.updateVehicleIntegrationLevel(vehicle.getName(),
                        Vehicle.IntegrationLevel.TO_BE_RESPECTED);
                // Since the vehicle is now disabled, release any order reservations
                // for it, too. Disabled vehicles should not keep reservations, and
                // this is a good fallback trigger to get rid of them in general.
                orderReservationPool.removeReservations(vehicle.getName());
            } else {
                VehicleService.INSTANCE.updateVehicleProcState(vehicle.getName(), Vehicle.ProcState.IDLE);
            }
        } else {
            LOG.debug("{}: Aborting order {}", vehicle.getName(), orderRef);
            abortAssignedOrder(TransportOrderService.INSTANCE.getOrderOrNull(orderRef), vehicle,
                    immediateAbort, disableVehicle);
        }
        // If requested, reset the vehicle position to null and free all resources.
        if (immediateAbort && resetVehiclePosition) {
            VehicleService.INSTANCE.updateVehicleIntegrationLevel(vehicle.getName(),
                    Vehicle.IntegrationLevel.TO_BE_IGNORED);
        }
    }

    public void abortOrder(TransportOrder order, boolean immediateAbort, boolean disableVehicle) {
        TransportOrder updatedOrder = TransportOrderService.INSTANCE.getOrderOrNull(order.getName());
        assert updatedOrder != null;
        String vehicleName = updatedOrder.getProcessingVehicle();

        // If the order is NOT currently being processed by any vehicle, just make
        // sure it's not going to be processed later, either.
        if (vehicleName == null) {
            if (!updatedOrder.getState().isFinalState()) {
                updateTransportOrderState(updatedOrder.getName(), TransportOrderState.FAILED);
                // The order was not processed by any vehicle but there still might be a reservation for
                // that order.
                orderReservationPool.removeReservation(updatedOrder.getName());
            }
        } else {
            abortAssignedOrder(updatedOrder, VehicleService.INSTANCE.getVehicle(vehicleName),
                    immediateAbort,
                    disableVehicle);
        }
    }

    /**
     * Aborts a given transport order known to be assigned to a given vehicle.
     *
     * @param vehicle        The vehicle the order is assigned to.
     * @param order          The order.
     * @param immediateAbort Whether to abort the order immediately instead of
     *                       just withdrawing it for a smooth abortion.
     * @param disableVehicle Whether to disable the vehicle, i.e. set its
     *                       procState to UNAVAILABLE.
     */
    private void abortAssignedOrder(TransportOrder order,
                                    Vehicle vehicle,
                                    boolean immediateAbort,
                                    boolean disableVehicle) {
        requireNonNull(order, "order");
        requireNonNull(vehicle, "vehicle");

        // Mark the order as withdrawn so we can react appropriately when the
        // vehicle reports the remaining movements as finished
        if (order.getState().isFinalState()) {
            return;
        } else if (!order.hasState(TransportOrderState.WITHDRAWN)) {
            updateTransportOrderState(order.getName(), TransportOrderState.WITHDRAWN);
        }

        VehicleController vehicleController = VehicleDriverManager.INSTANCE.getVehicleController(vehicle.getName());

        if (immediateAbort) {
            LOG.info("{}: Immediate abort of transport order {}...",
                    vehicle.getName(),
                    order.getName());
            vehicleController.clearDriveOrder();
            vehicleController.clearCommandQueue();
            finishAbortion(order.getName(), vehicle, disableVehicle);
        } else {
            if (disableVehicle) {
                // Remember that the vehicle should be disabled after finishing the
                // orders it already received.
                LOG.debug("{}: To be disabled later", vehicle.getName());
                vehiclesToDisable.add(vehicle.getName());
            }
            vehicleController.abortDriveOrder();

            // 非立即情况下，快速停止运单，但是要求机器人在站点上
            if (RouteConfigKt.getRouteConfig().getDispatcher().getFastWithdrawal()) {
                vehicleController.fastAbortDriveOrder();
            }
            // XXX What if the controller does not have any more movements to be
            // finished? Will it ever re-dispatch the vehicle in that case?
        }
    }

    /**
     * Properly sets a transport order to a finished state, setting related
     * properties.
     *
     * @param orderName A reference to the transport order to be modified.
     * @throws ObjectUnknownException If the referenced order could not be found.
     */
    private void setTOStateFinished(String orderName) {
        requireNonNull(orderName, "orderName");

        // Set the transport order's state.
        if (TransportOrderService.INSTANCE.getOrderOrNull(orderName).getState().isFinalState()) {
            // already in final state, leave it untouched
            // return;
            LOG.debug("Order {} has state: {}, setting to FINISHED.", orderName,
                    TransportOrderService.INSTANCE.getOrderOrNull(orderName).getState());
        }
        TransportOrderService.INSTANCE.updateTransportOrderState(orderName, TransportOrderState.FINISHED);
        TransportOrder order = TransportOrderService.INSTANCE.getOrderOrNull(orderName);
        // If it is part of an order sequence, we should proceed to its next order.
        if (order.getWrappingSequence() != null) {
            OrderSequence seq = OrderSequenceService.INSTANCE.getSequenceOrNull(order.getWrappingSequence());
            // Sanity check: The finished order must be the next one in the sequence;
            // if it is not, something has already gone wrong.
            checkState(orderName.equals(seq.getNextUnfinishedOrder()),
                    "Finished TO %s != next unfinished TO %s in sequence %s",
                    orderName,
                    seq.getNextUnfinishedOrder(),
                    seq);
            OrderSequenceService.INSTANCE.updateOrderSequenceFinishedIndex(seq.getName(), seq.getFinishedIndex() + 1);
            // Get an up-to-date copy of the order sequence
            seq = OrderSequenceService.INSTANCE.getSequenceOrNull(seq.getName());
            // If the sequence is complete and this was its last order, the sequence
            // is also finished.
            if (seq.getComplete() && getNextUnfinishedOrderFromSequence(seq.getName()) == null) {
                OrderSequenceService.INSTANCE.markOrderSequenceFinished(seq.getName());
                // Reset the processing vehicle's back reference on the sequence.
                VehicleService.INSTANCE.updateVehicleOrderSequence(seq.getProcessingVehicle(), null);
            }
        }
    }

    /**
     * Properly sets a transport order to a failed state, setting related
     * properties.
     *
     * @param orderName A reference to the transport order to be modified.
     * @throws ObjectUnknownException If the referenced order could not be found.
     */
    private void setTOStateFailed(String orderName) {
        requireNonNull(orderName, "orderName");

        TransportOrder failedOrder = TransportOrderService.INSTANCE.getOrderOrNull(orderName);
        TransportOrderService.INSTANCE.updateTransportOrderState(orderName, TransportOrderState.FAILED);
        // A transport order has failed - check if it's part of an order
        // sequence that we need to take care of.
        if (failedOrder.getWrappingSequence() == null) {
            return;
        }
        OrderSequence sequence = OrderSequenceService.INSTANCE.getSequenceOrNull(failedOrder.getWrappingSequence());
        if (sequence == null) {
            return;
        }

        if (sequence.getFailureFatal()) { // if it is current order
            // Mark the sequence as complete to make sure no further orders are
            // added.
            OrderSequenceService.INSTANCE.markOrderSequenceComplete(sequence.getName());
            // Mark all orders of the sequence that are not in a final state as
            // FAILED.
            sequence.getOrders().stream()
                    .map(TransportOrderService.INSTANCE::getOrderOrNull)
                    .filter(o -> !o.getState().isFinalState())
                    .forEach(o -> abortOrder(o, false, false));
            // Move the finished index of the sequence to its end.
            OrderSequenceService.INSTANCE.updateOrderSequenceFinishedIndex(sequence.getName(), sequence.getOrders().size() - 1);
        } else {
            if (sequence.getOrders().indexOf(orderName) == sequence.getFinishedIndex()) { // if it is current order
                // Since failure of an order in the sequence is not fatal, increment the
                // finished index of the sequence by one to move to the next order.
                OrderSequenceService.INSTANCE.updateOrderSequenceFinishedIndex(sequence.getName(), sequence.getFinishedIndex() + 1);
            }
        }
        // The sequence may have changed. Get an up-to-date copy.
        sequence = OrderSequenceService.INSTANCE.getSequenceOrNull(failedOrder.getWrappingSequence());
        // Mark the sequence as finished if there's nothing more to do in it.
        if (sequence.getComplete() && getNextUnfinishedOrderFromSequence(sequence.getName()) == null) {
            OrderSequenceService.INSTANCE.markOrderSequenceFinished(sequence.getName());
            // If the sequence was assigned to a vehicle, reset its back reference
            // on the sequence to make it available for orders again.
            if (sequence.getProcessingVehicle() != null) {
                VehicleService.INSTANCE.updateVehicleOrderSequence(sequence.getProcessingVehicle(), null);
            }
        }
    }

    /**
     * Get next unfinished transport order from given sequence.
     */
    private String getNextUnfinishedOrderFromSequence(String seqName) {
        OrderSequence sequence = OrderSequenceService.INSTANCE.getSequenceOrNull(seqName);
        // no order any more
        if (sequence.getFinishedIndex() + 1 >= sequence.getOrders().size()) {
            return null;
        }
        // get the next order
        String order = sequence.getOrders().get(sequence.getFinishedIndex() + 1);
        while (TransportOrderService.INSTANCE.getOrderOrNull(order).getState().isFinalState()) {
            LOG.info("transport order: {} is in final state, skipping...", order);
            // update the sequence
            OrderSequenceService.INSTANCE.updateOrderSequenceFinishedIndex(sequence.getName(), sequence.getFinishedIndex() + 1);
            // get a new copy of sequence
            sequence = OrderSequenceService.INSTANCE.getSequenceOrNull(seqName);
            // no order any more
            if (sequence.getFinishedIndex() + 1 >= sequence.getOrders().size()) {
                return null;
            }
            order = sequence.getOrders().get(sequence.getFinishedIndex() + 1);
        }
        return order;
    }
}
