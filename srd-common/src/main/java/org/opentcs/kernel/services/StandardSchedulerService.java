package org.opentcs.kernel.services;

import static java.util.Objects.requireNonNull;

import javax.inject.Inject;

import com.seer.srd.route.WhiteBoardKt;
import org.opentcs.access.SchedulerAllocationState;
import org.opentcs.components.kernel.Scheduler;
import org.opentcs.components.kernel.services.SchedulerService;
import java.util.Set;

/**
 * This class is the standard implementation of the {@link SchedulerService} interface.
 */
public class StandardSchedulerService implements SchedulerService {

    private final Scheduler scheduler;

    @Inject
    public StandardSchedulerService(Scheduler scheduler) {
        this.scheduler = requireNonNull(scheduler, "scheduler");
    }

    @Override
    public SchedulerAllocationState fetchSchedulerAllocations() {
        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            return new SchedulerAllocationState(scheduler.getAllocations());
        }
    }

    @Override
    public Set<String> fetchSchedulerAllocationsByName(String name) {
        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            return scheduler.getAllocationsByName(name);
        }
    }
}
