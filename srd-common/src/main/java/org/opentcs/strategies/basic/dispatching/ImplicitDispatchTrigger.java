package org.opentcs.strategies.basic.dispatching;

import com.seer.srd.eventbus.VehicleChangedEvent;
import org.opentcs.components.kernel.Dispatcher;
import com.seer.srd.vehicle.Vehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Objects.requireNonNull;

/**
 * An event listener that triggers dispatching of vehicles and transport orders on certain events.
 */
public class ImplicitDispatchTrigger {

    private static final Logger LOG = LoggerFactory.getLogger(ImplicitDispatchTrigger.class);

    private final Dispatcher dispatcher;

    public ImplicitDispatchTrigger(Dispatcher dispatcher) {
        this.dispatcher = requireNonNull(dispatcher, "dispatcher");
    }

    public Object onVehicleChanged(VehicleChangedEvent event) {
        //LOG.debug("event: {}", event);
        if (event.getOldVehicle() == null || event.getNewVehicle() == null) return "";
        checkVehicleChange(event.getOldVehicle(), event.getNewVehicle());
        return "";
    }

    private void checkVehicleChange(Vehicle oldVehicle, Vehicle newVehicle) {
        if ((newVehicle.getIntegrationLevel() == Vehicle.IntegrationLevel.TO_BE_UTILIZED
                || newVehicle.getIntegrationLevel() == Vehicle.IntegrationLevel.TO_BE_RESPECTED)
                && (idleAndEnergyLevelChanged(oldVehicle, newVehicle)
                || awaitingNextOrder(oldVehicle, newVehicle)
                || orderSequenceNulled(oldVehicle, newVehicle))) {
            LOG.debug("Dispatching for {}...", newVehicle);
            dispatcher.dispatch();
        }
    }

    private boolean idleAndEnergyLevelChanged(Vehicle oldVehicle, Vehicle newVehicle) {
        // If the vehicle is idle and its energy level changed, we may want to order it to recharge.
        return newVehicle.getProcState() == Vehicle.ProcState.IDLE
                && (newVehicle.getState() == Vehicle.State.IDLE || newVehicle.getState() == Vehicle.State.CHARGING)
                && newVehicle.getEnergyLevel() != oldVehicle.getEnergyLevel();
    }

    private boolean awaitingNextOrder(Vehicle oldVehicle, Vehicle newVehicle) {
        // If the vehicle's processing state changed to IDLE or AWAITING_ORDER, it is waiting for
        // its next order, so look for one.
        return newVehicle.getProcState() != oldVehicle.getProcState()
                && (newVehicle.getProcState() == Vehicle.ProcState.IDLE
                || newVehicle.getProcState() == Vehicle.ProcState.AWAITING_ORDER);
    }

    private boolean orderSequenceNulled(Vehicle oldVehicle, Vehicle newVehicle) {
        // If the vehicle's order sequence reference has become null, the vehicle has just been released
        // from an order sequence, so we may look for new assignments.
        return newVehicle.getOrderSequence() == null
                && oldVehicle.getOrderSequence() != null;
    }
}
