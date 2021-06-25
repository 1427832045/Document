package org.opentcs.access;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Represents the current state of resource allocations.
 */
public class SchedulerAllocationState implements Serializable {

    /**
     * The current state of allocations.
     */
    private final Map<String, Set<String>> allocationStates;

    /**
     * Creates a new instance.
     *
     * @param allocationStates The current state of allocations.
     */
    public SchedulerAllocationState(@Nonnull Map<String, Set<String>> allocationStates) {
        this.allocationStates = Collections.unmodifiableMap(requireNonNull(allocationStates, "allocationStates"));
    }

    /**
     * Returns the current state of allocations.
     *
     * @return The current state of allocations.
     */
    public Map<String, Set<String>> getAllocationStates() {
        return allocationStates;
    }
}
