package org.opentcs.strategies.basic.dispatching.selection.orders;

import com.seer.srd.route.service.OrderSequenceService;
import org.opentcs.data.order.OrderSequence;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.data.order.TransportOrderState;
import org.opentcs.strategies.basic.dispatching.OrderReservationPool;
import org.opentcs.strategies.basic.dispatching.selection.TransportOrderSelectionFilter;

import javax.inject.Inject;

import static java.util.Objects.requireNonNull;

/**
 * Filters transport orders that are dispatchable and available to <em>any</em> vehicle.
 */
public class IsFreelyDispatchableToAnyVehicle
        implements TransportOrderSelectionFilter {

    private final OrderReservationPool orderReservationPool;

    @Inject
    public IsFreelyDispatchableToAnyVehicle(OrderReservationPool orderReservationPool) {
        this.orderReservationPool = requireNonNull(orderReservationPool, "orderReservationPool");
    }

    @Override
    public boolean test(TransportOrder order) {
        // We only want to check dispatchable transport orders.
        // Filter out transport orders that are intended for other vehicles.
        // Also filter out all transport orders with reservations. We assume that a check for reserved
        // orders has been performed already, and if any had been found, we wouldn't have been called.
        return order.hasState(TransportOrderState.DISPATCHABLE)
                && !partOfAnyVehiclesSequence(order)
                && !orderReservationPool.isReserved(order.getName());
    }

    private boolean partOfAnyVehiclesSequence(TransportOrder order) {
        if (order.getWrappingSequence() == null) {
            return false;
        }
        OrderSequence seq = OrderSequenceService.INSTANCE.getSequenceOrNull(order.getWrappingSequence());
        return seq != null && seq.getProcessingVehicle() != null;
    }
}
