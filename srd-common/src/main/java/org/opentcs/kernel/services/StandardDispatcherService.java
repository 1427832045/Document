package org.opentcs.kernel.services;

import com.seer.srd.route.WhiteBoardKt;
import com.seer.srd.route.service.TransportOrderService;
import com.seer.srd.route.service.VehicleService;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.components.kernel.Dispatcher;
import org.opentcs.components.kernel.services.DispatcherService;
import org.opentcs.data.ObjectUnknownException;
import org.opentcs.data.order.TransportOrder;

import javax.inject.Inject;

import static java.util.Objects.requireNonNull;

/**
 * This class is the standard implementation of the {@link DispatcherService} interface.
 */
public class StandardDispatcherService implements DispatcherService {

    private final Dispatcher dispatcher;

    @Inject
    public StandardDispatcherService(Dispatcher dispatcher) {
        this.dispatcher = requireNonNull(dispatcher, "dispatcher");
    }

    @Override
    public void dispatch() {
        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            dispatcher.dispatch();
        }
    }

    @Override
    public void withdrawByVehicle(String vehicleName, boolean immediateAbort, boolean disableVehicle)
            throws ObjectUnknownException {
        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            Vehicle vehicle = VehicleService.INSTANCE.getVehicleOrNull(vehicleName);
            if (vehicle == null) return;
            dispatcher.withdrawOrder(vehicle, immediateAbort, disableVehicle);
        }
    }

    @Override
    public void withdrawByVehicle(String vehicleName, boolean immediateAbort) throws ObjectUnknownException {
        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            Vehicle vehicle = VehicleService.INSTANCE.getVehicleOrNull(vehicleName);
            if (vehicle == null) return;
            dispatcher.withdrawOrder(vehicle, immediateAbort);
        }
    }

    @Override
    public void withdrawByTransportOrder(String orderName, boolean immediateAbort)
            throws ObjectUnknownException {
        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            TransportOrder order = TransportOrderService.INSTANCE.getOrderOrNull(orderName);
            if (order == null) return;
            dispatcher.withdrawOrder(order, immediateAbort);
        }
    }

    @Override
    public void withdrawByTransportOrder(String orderName, boolean immediateAbort, boolean disableVehicle)
            throws ObjectUnknownException {
        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            TransportOrder order = TransportOrderService.INSTANCE.getOrderOrNull(orderName);
            if (order == null) return;
            dispatcher.withdrawOrder(order, immediateAbort, disableVehicle);
        }
    }
}
