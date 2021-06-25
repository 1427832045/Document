package org.opentcs.drivers.vehicle.synchronizer;

import javax.annotation.Nonnull;

import org.opentcs.util.ExplainedBoolean;
import org.opentcs.drivers.vehicle.MovementCommand;

/**
 * An interface of device synchronizer.
 */
public interface SynchronizeRequest {

    /**
     * The function will request the device synchronizer, and give back a
     * explained boolean result.
     *
     * @param vehicle The vehicle's name.
     * @param command The movementcommand from current step.
     * @return An explained boolean result.
     */
    ExplainedBoolean request(String vehicle, @Nonnull MovementCommand command);
}