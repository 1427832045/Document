package org.opentcs.components.kernel.services;

import org.opentcs.access.KernelRuntimeException;
import org.opentcs.access.SchedulerAllocationState;
import org.opentcs.components.kernel.Scheduler;
import java.util.Set;

/**
 * Provides methods concerning the {@link Scheduler}.
 */
public interface SchedulerService {

    /**
     * Returns the current state of resource allocations.
     *
     * @return The current state of resource allocations.
     * @throws KernelRuntimeException In case there is an exception executing this method.
     */
    SchedulerAllocationState fetchSchedulerAllocations()
            throws KernelRuntimeException;

    Set<String> fetchSchedulerAllocationsByName(String name)
            throws KernelRuntimeException;
}
