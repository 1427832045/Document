package org.opentcs.drivers.vehicle;

import java.util.Map;

import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.seer.srd.model.Location;
import com.seer.srd.model.Point;
import org.opentcs.data.order.Destination;
import org.opentcs.data.order.Step;

/**
 * A command for moving a step.
 */
public class MovementCommand {

    // The id generator of movement command.
//    private final static Map<String, Integer> commandIdGenerator = new HashMap<>();

    /**
     * A constant indicating there is no operation to be executed after moving.
     */
    public static final String NO_OPERATION = Destination.OP_NOP;
    /**
     * A constant indicating the vehicle should basically just move to a point
     * without a location associated to it.
     */
    public static final String MOVE_OPERATION = Destination.OP_MOVE;
    /**
     * A constant for parking the vehicle. (Again, basically doing nothing at the
     * destination.)
     */
    public static final String PARK_OPERATION = Destination.OP_PARK;

    // The step describing the movement.
    private final Step step;

    // The operation to be executed after moving.
    private final String operation;

    // The location at which the operation is to be executed. (May be
    // <code>null</code> if <em>operation</em> is <code>NO_OPERATION</code>.)
    private final Location opLocation;

    // Indicates whether this movement is the final one for the drive order it belongs to.
    private final boolean finalMovement;

    // The destination position of the whole drive order.
    private final Point finalDestination;

    // The destination location of the whole drive order.
    private final Location finalDestinationLocation;

    // The operation to be executed at the destination position.
    private final String finalOperation;

    private final Map<String, String> properties;

    private String id;

    @Deprecated
    public MovementCommand(Step newStep,
                           String newOperation,
                           Location newOpLocation,
                           boolean finalMovement,
                           Point newDestination,
                           String newDestOperation,
                           Map<String, String> newProperties) {
        this(newStep,
                newOperation,
                newOpLocation,
                finalMovement,
                null,
                newDestination,
                newDestOperation,
                newProperties,
                "Vehicle-None",
                "TransportOrder-None",
                -1);
    }

    public MovementCommand(@Nonnull Step step,
                           @Nonnull String operation,
                           @Nullable Location opLocation,
                           boolean finalMovement,
                           @Nullable Location finalDestinationLocation,
                           @Nonnull Point finalDestination,
                           @Nonnull String finalOperation,
                           @Nonnull Map<String, String> properties,
                           @Nonnull String vehicle,
                           @Nonnull String transportOrdername,
                           @Nonnull Integer currentDriveOrderIndex) {
        this.step = requireNonNull(step, "step");
        this.operation = requireNonNull(operation, "operation");
        this.finalMovement = finalMovement;
        this.finalDestinationLocation = finalDestinationLocation;
        this.finalDestination = requireNonNull(finalDestination, "finalDestination");
        this.finalOperation = requireNonNull(finalOperation, "finalOperation");
        this.properties = requireNonNull(properties, "properties");
        if (opLocation == null && !isEmptyOperation(operation)) {
            throw new NullPointerException("opLocation");
        }
        this.opLocation = opLocation;
        long hashId = (transportOrdername.hashCode() & Integer.MAX_VALUE) * 100000000L +
                (step.hashCode() & Integer.MAX_VALUE) * 10L +
                (currentDriveOrderIndex + 1) + step.getRouteIndex() + System.currentTimeMillis();
        this.id = vehicle + ":" + hashId;
//        Integer intId = commandIdGenerator.getOrDefault(vehicle, 0);
//        this.id = vehicle + ":" + intId;
//        commandIdGenerator.put(vehicle, ++intId);
    }

    @Nonnull
    public Step getStep() {
        return step;
    }

    @Nonnull
    public String getOperation() {
        return operation;
    }

    /**
     * Checks whether an operation is to be executed in addition to moving or not.
     */
    public boolean isWithoutOperation() {
        return isEmptyOperation(operation);
    }

    @Nullable
    public Location getOpLocation() {
        return opLocation;
    }

    public boolean isFinalMovement() {
        return finalMovement;
    }

    @Nonnull
    public Point getFinalDestination() {
        return finalDestination;
    }

    @Nullable
    public Location getFinalDestinationLocation() {
        return finalDestinationLocation;
    }

    @Nonnull
    public String getFinalOperation() {
        return finalOperation;
    }

    @Nonnull
    public Map<String, String> getProperties() {
        return properties;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof MovementCommand) {
            MovementCommand other = (MovementCommand) o;
            return step.equals(other.step) &&
                    operation.equals(other.operation) &&
                    id.equals(other.id);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return step.hashCode() ^ operation.hashCode() ^ properties.hashCode() ^ id.hashCode();
    }

    @Override
    public String toString() {
        return "MovementCommand{"
                + "id=" + id
                + ", step=" + step
                + ", operation=" + operation
                + ", opLocation=" + opLocation
                + ", finalMovement=" + finalMovement
                + ", finalDestination=" + finalDestination
                + ", finalDestinationLocation=" + finalDestinationLocation
                + ", finalOperation=" + finalOperation
                + ", properties=" + properties
                + '}';
    }

    /**
     * Checks whether an operation means something is to be done in addition to
     * moving or not.
     */
    private static boolean isEmptyOperation(String operation) {
        return NO_OPERATION.equals(operation)
                || MOVE_OPERATION.equals(operation)
                || PARK_OPERATION.equals(operation);
    }

    public String getId() {
        return id;
    }

    public void setId(String id){
        this.id = id;
    }
}
