/**
 * Copyright (c) The openTCS Authors.
 * <p>
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.strategies.basic.dispatching;

import com.seer.srd.route.service.TransportOrderService;
import com.seer.srd.route.service.VehicleService;
import org.opentcs.components.kernel.services.DispatcherService;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.data.order.TransportOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

import static java.util.Objects.requireNonNull;

/**
 * Periodically checks for idle vehicles that could process a transport order.
 * The main purpose of doing this is retrying to dispatch vehicles that were not in a dispatchable
 * state when dispatching them was last tried.
 * A potential reason for this is that a vehicle temporarily reported an error because a safety
 * sensor was triggered.
 *
 * @author Stefan Walter (Fraunhofer IML)
 */
public class PeriodicVehicleRedispatchingTask
        implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(PeriodicVehicleRedispatchingTask.class);

    private final DispatcherService dispatcherService;

    @Inject
    public PeriodicVehicleRedispatchingTask(DispatcherService dispatcherService) {
        this.dispatcherService = requireNonNull(dispatcherService, "dispatcherService");
    }

    @Override
    public void run() {
        // If there are any vehicles that could process a transport order,
        // trigger the dispatcher once.
        VehicleService.INSTANCE.listVehicles().stream().filter(this::couldProcessTransportOrder)
                .findAny()
                .ifPresent(vehicle -> {
                    LOG.debug("Vehicle {} could process transport order, triggering dispatcher ...", vehicle);
                    dispatcherService.dispatch();
                });
    }

    private boolean couldProcessTransportOrder(Vehicle vehicle) {
        return vehicle.getIntegrationLevel() == Vehicle.IntegrationLevel.TO_BE_UTILIZED
                && vehicle.getCurrentPosition() != null
                && (processesNoOrder(vehicle)
                || processesDispensableOrder(vehicle)
                || TransportOrderService.INSTANCE.getNeedRecover());
    }

    private boolean processesNoOrder(Vehicle vehicle) {
        return vehicle.hasProcState(Vehicle.ProcState.IDLE)
                && (vehicle.hasState(Vehicle.State.IDLE)
                || vehicle.hasState(Vehicle.State.CHARGING));
    }

    private boolean processesDispensableOrder(Vehicle vehicle) {
        TransportOrder order = TransportOrderService.INSTANCE.getOrderOrNull(vehicle.getTransportOrder());
        return vehicle.hasProcState(Vehicle.ProcState.PROCESSING_ORDER) && order != null && order.isDispensable();
    }
}
