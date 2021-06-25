package org.opentcs.components.kernel;

import com.seer.srd.model.Point;
import org.opentcs.components.Lifecycle;
import com.seer.srd.vehicle.Vehicle;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * A strategy for finding parking positions for vehicles.
 * @deprecated Implementation-specific interface does not belong into generic API.
 * Moved to implementation.
 */
@Deprecated
public interface ParkingPositionSupplier extends Lifecycle {

    /**
     * Returns a suitable parking position for the given vehicle.
     *
     * @param vehicle The vehicle to find a parking position for.
     * @return A parking position for the given vehicle, or an empty Optional, if no suitable parking
     * position is available.
     */
    @Nonnull
    Optional<Point> findParkingPosition(@Nonnull Vehicle vehicle);
}
