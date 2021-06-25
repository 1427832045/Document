package org.opentcs.data.order;

import javax.annotation.Nonnull;
import java.io.Serializable;

import static java.util.Objects.requireNonNull;

/**
 * Describes the rejection of a transport order by a vehicle, and the reason given for the vehicle
 * rejecting the order.
 */
public class Rejection implements Serializable {

    /**
     * The vehicle that rejected the transport order.
     * May not be <code>null</code>.
     */
    private final String vehicle;
    /**
     * The reason given for rejecting the transport order.
     * May not be <code>null</code>.
     */
    private final String reason;
    /**
     * The point of time at which the transport order was rejected/this Rejection
     * was created.
     */
    private final long timestamp;

    /**
     * Creates a new Rejection.
     *
     * @param vehicle The vehicle that rejected the transport order.
     * @param reason  The reason given for rejecting the transport order.
     */
    public Rejection(@Nonnull String vehicle, @Nonnull String reason) {
        this.vehicle = requireNonNull(vehicle, "vehicle");
        this.reason = requireNonNull(reason, "reason");
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Returns the reason given for rejecting the transport order.
     *
     * @return The reason given for rejecting the transport order.
     */
    @Nonnull
    public String getReason() {
        return reason;
    }

    /**
     * Returns the point of time at which the transport order was rejected/this
     * Rejection was created.
     *
     * @return The point of time at which the transport order was rejected.
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the vehicle that rejected the transport order.
     *
     * @return The vehicle that rejected the transport order.
     */
    @Nonnull
    public String getVehicle() {
        return vehicle;
    }

    @Override
    public String toString() {
        return "Rejection{"
                + "vehicle=" + vehicle
                + ", reason=" + reason
                + ", timestamp=" + timestamp
                + '}';
    }
}
