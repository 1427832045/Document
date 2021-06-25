/**
 * Copyright (c) The openTCS Authors.
 * <p>
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.drivers.vehicle.synchronizer;

import javax.annotation.Nonnull;

import org.opentcs.util.ExplainedBoolean;
import org.opentcs.drivers.vehicle.MovementCommand;

/**
 * A device synchronizer.
 */
public interface DeviceSynchronizer {

    /**
     * Request the device synchronizer before send command.
     *
     * @param vehicle The vehicle's name.
     * @param command The movementcommand from current step.
     * @return An explained boolean result.
     */
    ExplainedBoolean requestBefore(@Nonnull String vehicle, @Nonnull MovementCommand command);

    /**
     * Query the device synchronizer before send command.
     *
     * @param vehicle The vehicle's name.
     * @param command The movementcommand from current step.
     * @return An explained boolean result.
     */
    ExplainedBoolean queryBefore(@Nonnull String vehicle, @Nonnull MovementCommand command);

    /**
     * Request the device synchronizer just at the time of a SendCommand.
     *
     * @param vehicle The vehicle's name.
     * @param command The movementcommand from current step.
     * @return An explained boolean result.
     */
    ExplainedBoolean requestAtSend(@Nonnull String vehicle, @Nonnull MovementCommand command);

    /**
     * Request the device synchronizer while current command is executing.
     *
     * @param vehicle The vehicle's name.
     * @param command The movementcommand from current step.
     * @return An explained boolean result.
     */
    ExplainedBoolean requestWhile(@Nonnull String vehicle, @Nonnull MovementCommand command);

    /**
     * Query the device synchronizer just at the time of a CommandExecuted.
     *
     * @param vehicle The vehicle's name.
     * @param command The movementcommand from current step.
     * @return An explained boolean result.
     */
    ExplainedBoolean queryAtExecuted(@Nonnull String vehicle, @Nonnull MovementCommand command);

    /**
     * Request the device synchronizer after CommandExecuted.
     *
     * @param vehicle The vehicle's name.
     * @param command The movementcommand from current step.
     * @return An explained boolean result.
     */
    ExplainedBoolean requestAfterExecuted(@Nonnull String vehicle, @Nonnull MovementCommand command);

}