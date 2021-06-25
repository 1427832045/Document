package org.opentcs.strategies.basic.dispatching;

import com.seer.srd.route.service.TransportOrderService;
import com.seer.srd.vehicle.driver.VehicleDriverManager;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.data.order.OrderConstants;
import org.opentcs.data.order.Rejection;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.util.ExplainedBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Checks processability of transport orders for vehicles.
 */
public class ProcessabilityChecker {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessabilityChecker.class);

    /**
     * Checks if the given vehicle could process the given order right now.
     *
     * @param vehicle The vehicle.
     * @param order   The order.
     * @return <code>true</code> if, and only if, the given vehicle can process
     * the given order.
     */
    public boolean checkProcessability(Vehicle vehicle, TransportOrder order) {
        requireNonNull(vehicle, "vehicle");
        requireNonNull(order, "order");

        // Check for matching categories
        if (!order.getCategory().equals(OrderConstants.CATEGORY_NONE)
                && !vehicle.getProcessableCategories().contains(OrderConstants.CATEGORY_ANY)
                && !vehicle.getProcessableCategories().contains(order.getCategory())) {
            LOG.debug("Category '{}' of order '{}' not in categories '{}' of vehicle '{}'.",
                    order.getCategory(),
                    order.getName(),
                    vehicle.getProcessableCategories(),
                    vehicle.getName());
            return false;
        }

        ExplainedBoolean result = VehicleDriverManager.INSTANCE.getVehicleController(vehicle.getName())
                .canProcess(operationSequence(order));
        if (result.getValue()) {
            return true;
        } else {
            // The vehicle controller/communication adapter does not want to process
            // the order. Add a rejection for it.
            Rejection rejection = new Rejection(vehicle.getName(), result.getReason());
            LOG.debug("Order '{}' rejected by vehicle '{}', reason: '{}'",
                    order.getName(),
                    vehicle.getName(),
                    rejection.getReason());
//            TransportOrderService.INSTANCE.registerTransportOrderRejection(order.getName(), rejection);
            return false;
        }
    }

    /**
     * Returns the sequence of operations to be executed when processing the given transport order.
     *
     * @param order The transport order from which to extract the sequence of operations.
     * @return The sequence of operations to be executed when processing the given transport order.
     */
    private List<String> operationSequence(TransportOrder order) {
        return order.getFutureDriveOrders().stream()
                .map(driveOrder -> driveOrder.getDestination().getOperation())
                .collect(Collectors.toList());
    }

}
