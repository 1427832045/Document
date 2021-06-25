package org.opentcs.drivers.vehicle;

import java.util.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.opentcs.components.Lifecycle;
import org.opentcs.components.kernel.Scheduler;
import org.opentcs.data.order.DriveOrder;
import org.opentcs.util.ExplainedBoolean;

/**
 * Provides high-level methods for the system to control a vehicle.
 */
public interface VehicleController extends Lifecycle, Scheduler.Client {

    /**
     * Sets the current drive order for the vehicle associated with this controller.
     */
    void setDriveOrder(@Nonnull DriveOrder newOrder, @Nonnull Map<String, String> orderProperties);

    /**
     * Updates the current drive order for the vehicle associated with this controller.
     * <p>
     * An update is only allowed, if the continuity of the current drive order is guaranteed.
     * The continuity of the current drive order is guaranteed, if the routes of both the current
     * drive order and the {@code newOrder} match to the point where the vehicle associated with this
     * controller is currently reported at. Beyond that point the routes may diverge.
     * </p>
     */
    default void updateDriveOrder(@Nonnull DriveOrder newOrder,
                                  @Nonnull Map<String, String> orderProperties) {
    }

    /**
     * Resets the current drive order for the vehicle associated with this controller.
     */
    void clearDriveOrder();

    /**
     * Notifies the controller that the current drive order is to be aborted.
     * After receiving this notification, the controller should not send any
     * further movement commands to the vehicle.
     */
    void abortDriveOrder();

    /**
     * 快速非立即撤销
     */
    void fastAbortDriveOrder();

    /**
     * Clears the associated vehicle's command queue and frees all resources reserved for the removed
     * commands/movements.
     */
    void clearCommandQueue();

    void safeClearCommandQueue();

    /**
     * Resets the vehicle's position and precise position to <code>null</code> and frees all resources
     * held by the vehicle.
     *
     * @deprecated Should be done via setting the vehicle's integration level.
     */
    @Deprecated
    void resetVehiclePosition();

    /**
     * Checks if the vehicle would be able to process the given sequence of
     * operations, taking into account its current state.
     *
     * @param operations A sequence of operations that might appear in future
     *                   commands.
     * @return A <code>Processability</code> telling if the vehicle would be able
     * to process every single operation in the list (in the given order).
     */
    @Nonnull
    ExplainedBoolean canProcess(@Nonnull List<String> operations);

    /**
     * Delivers a generic message to the communication adapter.
     */
    void sendCommAdapterMessage(@Nullable Object message);

    /**
     * Get the error information of vehicle.
     */
    List<VehicleProcessModel.ErrorInfo> getErrorInfos();

    /**
     * Get the detail information of vehicle.
     */
    String getDetails();

    /**
     * Sends a {@link AdapterCommand} to the communication adapter.
     */
    default void sendCommAdapterCommand(@Nonnull AdapterCommand command) {
    }

    /**
     * Returns a list of {@link MovementCommand}s that have been sent to the communication adapter.
     */
    @Nonnull
    default Queue<MovementCommand> getCommandsSent() {
        return new LinkedList<>();
    }

    DriveOrder getCurrentDriveOrder();

    @Nullable
    Long getLatestSendOrFinishCommandTime();
}
