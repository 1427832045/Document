package org.opentcs.strategies.basic.dispatching.phase;

import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.service.TransportOrderService;
import org.opentcs.components.kernel.Router;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.data.order.TransportOrderState;
import org.opentcs.strategies.basic.dispatching.Phase;
import org.opentcs.strategies.basic.dispatching.TransportOrderUtil;

import javax.inject.Inject;

import static java.util.Objects.requireNonNull;

/**
 * Checks for transport orders that are still in state RAW, and attempts to prepare them for
 * assignment.
 */
public class CheckNewOrdersPhase implements Phase {

    private final Router router;

    private final TransportOrderUtil transportOrderUtil;

    private boolean initialized;

    @Inject
    public CheckNewOrdersPhase(Router router,
                               TransportOrderUtil transportOrderUtil) {
        this.router = requireNonNull(router, "router");
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
        TransportOrderService.INSTANCE.listUnfinishedOrders()
                .stream().filter(this::inRawState)
                .forEach(order -> checkRawTransportOrder(order));
    }

    private void checkRawTransportOrder(TransportOrder order) {
        requireNonNull(order, "order");

        // Check if the transport order is routable.
        if (RouteConfigKt.getRouteConfig().getDispatcher().getDismissUnroutableTransportOrders()
                && router.checkRoutability(order).isEmpty()) {
            transportOrderUtil.updateTransportOrderState(order.getName(), TransportOrderState.UNROUTABLE);
            return;
        }
        transportOrderUtil.updateTransportOrderState(order.getName(), TransportOrderState.ACTIVE);
        // The transport order has been activated - dispatch it.
        // Check if it has unfinished dependencies.
        if (!transportOrderUtil.hasUnfinishedDependencies(order)) {
            transportOrderUtil.updateTransportOrderState(order.getName(), TransportOrderState.DISPATCHABLE);
        }
    }

    private boolean inRawState(TransportOrder order) {
        return order.hasState(TransportOrderState.RAW);
    }
}
