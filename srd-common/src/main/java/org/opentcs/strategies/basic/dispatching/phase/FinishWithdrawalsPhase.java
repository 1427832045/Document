package org.opentcs.strategies.basic.dispatching.phase;

import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.service.TransportOrderService;
import com.seer.srd.route.service.VehicleService;
import com.seer.srd.vehicle.Vehicle;
import com.seer.srd.vehicle.driver.VehicleDriverManager;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.data.order.TransportOrderState;
import org.opentcs.drivers.vehicle.VehicleController;
import org.opentcs.strategies.basic.dispatching.Phase;
import org.opentcs.strategies.basic.dispatching.TransportOrderUtil;

import javax.inject.Inject;

import static java.util.Objects.requireNonNull;

/**
 * Finishes withdrawals of transport orders after the vehicle has come to a halt.
 */
public class FinishWithdrawalsPhase implements Phase {

    private final TransportOrderUtil transportOrderUtil;

    private boolean initialized;

    @Inject
    public FinishWithdrawalsPhase(TransportOrderUtil transportOrderUtil) {
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
        if (RouteConfigKt.getRouteConfig().getDispatcher().getFastWithdrawal()) {
            VehicleService.INSTANCE.listVehicles().stream()
                    .filter(this::hasWithdrawnTransportOrder)
                    .filter(vehicle -> vehicle.getState() == Vehicle.State.IDLE || vehicle.getState() == Vehicle.State.CHARGING)
                    .forEach(this::finishFastAbortion);
        } else {
            VehicleService.INSTANCE.listVehicles().stream()
                    .filter(vehicle -> vehicle.getProcState() == Vehicle.ProcState.AWAITING_ORDER)
                    .filter(this::hasWithdrawnTransportOrder)
                    .forEach(transportOrderUtil::finishAbortion);
        }
    }

    private boolean hasWithdrawnTransportOrder(Vehicle vehicle) {
        TransportOrder order = TransportOrderService.INSTANCE.getOrderOrNull(vehicle.getTransportOrder());
        if (order == null) return false;
        return order.hasState(TransportOrderState.WITHDRAWN);
    }

    private void finishFastAbortion(Vehicle vehicle) {
        VehicleController vehicleController = VehicleDriverManager.INSTANCE.getVehicleController(vehicle.getName());
        vehicleController.clearDriveOrder();
        vehicleController.safeClearCommandQueue();
        transportOrderUtil.finishAbortion(vehicle);
    }

}
