package org.opentcs.strategies.basic.scheduling;

import com.seer.srd.route.WhiteBoardKt;
import org.opentcs.components.kernel.ResourceAllocationException;
import org.opentcs.components.kernel.Scheduler;
import org.opentcs.strategies.basic.scheduling.AllocatorCommand.Allocate;
import org.opentcs.strategies.basic.scheduling.AllocatorCommand.AllocationsReleased;
import org.opentcs.strategies.basic.scheduling.AllocatorCommand.CheckAllocationsPrepared;
import org.opentcs.strategies.basic.scheduling.AllocatorCommand.RetryAllocates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkPositionIndex;
import static java.util.Objects.requireNonNull;

/**
 * Implements a basic simple scheduler strategy for resources used by vehicles, preventing collisions.
 */
public class DefaultScheduler implements Scheduler {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultScheduler.class);

    private final Module allocationAdvisor;
    /**
     * All claims.
     */
    private final Map<Client, List<Set<String>>> claimsByClient = new HashMap<>();

    private final ReservationPool reservationPool;
    /**
     * Allocations deferred because they couldn't be granted, yet.
     */
    private final Queue<AllocatorCommand.Allocate> deferredAllocations = new LinkedBlockingQueue<>();
    /**
     * Indicates whether this component is enabled.
     */
    private boolean initialized;


    @Inject
    public DefaultScheduler(AllocationAdvisor allocationAdvisor,
                            ReservationPool reservationPool) {
        this.allocationAdvisor = requireNonNull(allocationAdvisor, "allocationAdvisor");
        this.reservationPool = requireNonNull(reservationPool, "reservationPool");
    }

    @Override
    public void initialize() {
        if (isInitialized()) return;

        reservationPool.clear();
        allocationAdvisor.initialize();

        initialized = true;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void terminate() {
        if (!isInitialized()) return;

        allocationAdvisor.terminate();
        claimsByClient.clear();
        reservationPool.clear();
        deferredAllocations.clear();
        initialized = false;
    }

    @Override
    public void claim(Client client, List<Set<String>> resources) {
        requireNonNull(client, "client");
        requireNonNull(resources, "resources");

        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            claimsByClient.put(client, resources);

            allocationAdvisor.claim(client, resources);
            allocationAdvisor.setAllocationState(client, reservationPool.allocatedResources(client), resources);
        }
    }

    @Override
    public void updateProgressIndex(Client client, int index) {
        requireNonNull(client, "client");
        checkPositionIndex(index, Integer.MAX_VALUE, "index");

        if (index == 0) return;
        // XXX Verify that the index is only incremented, never decremented?

        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            List<Set<String>> claims = claimsByClient.get(client);
            List<Set<String>> remainingClaims = claims.subList(index, claims.size());
            allocationAdvisor.setAllocationState(client,
                    reservationPool.allocatedResources(client),
                    remainingClaims);
        }
    }

    @Override
    public void unclaim(Client client) {
        requireNonNull(client, "client");

        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            claimsByClient.remove(client);

            allocationAdvisor.setAllocationState(client,
                    reservationPool.allocatedResources(client),
                    new LinkedList<>());
            allocationAdvisor.unclaim(client);
        }
    }

    @Override
    public void allocate(Client client, Set<String> resources) {
        requireNonNull(client, "client");
        requireNonNull(resources, "resources");

        WhiteBoardKt.getKernelExecutor().submit(new AllocatorTask(reservationPool,
                deferredAllocations,
                allocationAdvisor,
                new Allocate(client, resources)));
    }

    @Override
    public void allocateNow(Client client, Set<String> resources)
            throws ResourceAllocationException {
        requireNonNull(client, "client");
        requireNonNull(resources, "resources");

        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            // Check if all resources are available.
            final Set<String> availableResources = new HashSet<>();
            for (String curResource : resources) {
                ReservationEntry entry = reservationPool.getReservationEntry(curResource);
                if (!entry.isFree() && !entry.isAllocatedBy(client)) {
                    LOG.warn("{}: Resource {} unavailable, reserved by {}",
                            client.getId(), curResource, entry.getClient().getId());
                    // XXX DO something about it?!
                } else if (!entry.isFree() && entry.isAllocatedBy(client)) {
                    LOG.info("{}: Resource {} is already reserved",
                            client.getId(), curResource);
                } else {
                    availableResources.add(curResource);
                }
            }
            // Allocate all requested resources that are available.
            LOG.debug("{}: Allocating immediately: {}", client.getId(), availableResources);
            for (String curResource : availableResources) {
                reservationPool.getReservationEntry(curResource).allocate(client);
            }
        }
    }

    @Override
    public void free(Client client, Set<String> resources) {
        requireNonNull(client, "client");
        requireNonNull(resources, "resources");

        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            LOG.debug("{}: Releasing resources: {}", client.getId(), resources);
            reservationPool.free(client, resources);

            // Check which resources are now completely free
            Set<String> completelyFreeResources = resources.stream()
                    .filter(resource -> reservationPool.getReservationEntry(resource).isFree())
                    .collect(Collectors.toCollection(HashSet::new));
            WhiteBoardKt.getKernelExecutor().submit(new AllocatorTask(
                    reservationPool,
                    deferredAllocations,
                    allocationAdvisor,
                    new AllocationsReleased(client, completelyFreeResources)));
        }
        WhiteBoardKt.getKernelExecutor().submit(new AllocatorTask(
                reservationPool,
                deferredAllocations,
                allocationAdvisor,
                new RetryAllocates(client)));
    }

    @Override
    public void freeAll(Client client) {
        requireNonNull(client, "client");

        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            Set<String> freedResources = reservationPool.allocatedResources(client);

            LOG.debug("{}: Releasing all resources...", client.getId());
            reservationPool.freeAll(client);
            LOG.debug("{}: Clearing pending allocation requests...", client.getId());
            deferredAllocations.removeIf(allocate -> client.equals(allocate.getClient()));

            WhiteBoardKt.getKernelExecutor().submit(new AllocatorTask(reservationPool,
                    deferredAllocations,
                    allocationAdvisor,
                    new AllocationsReleased(client, freedResources)));
        }

        WhiteBoardKt.getKernelExecutor().submit(new AllocatorTask(reservationPool,
                deferredAllocations,
                allocationAdvisor,
                new RetryAllocates(client)));
    }

    @Override
    public void freeAllExcept(@Nonnull Client client, @Nonnull Set<String> exceptResources) {
        requireNonNull(client, "client");
        requireNonNull(exceptResources, "exceptResources");

        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            Set<String> freedResources = reservationPool.allocatedResources(client);
            exceptResources.forEach(freedResources::remove);

            LOG.debug("{}: Releasing all resources except {}...", client.getId(), exceptResources);
            reservationPool.freeAllExcept(client, exceptResources);
            LOG.debug("{}: Clearing pending allocation requests...", client.getId());
            deferredAllocations.removeIf(allocate -> client.equals(allocate.getClient()));

            WhiteBoardKt.getKernelExecutor().submit(new AllocatorTask(reservationPool,
                    deferredAllocations,
                    allocationAdvisor,
                    new AllocationsReleased(client, freedResources)));
        }
        WhiteBoardKt.getKernelExecutor().submit(new AllocatorTask(reservationPool,
                deferredAllocations,
                allocationAdvisor,
                new RetryAllocates(client)));
    }

    @Override
    public boolean isAllocatedBy(@Nonnull String resource, @Nonnull Client client) {
        requireNonNull(client, "client");
        requireNonNull(resource, "resource");

        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            ReservationEntry entry = reservationPool.getReservationEntry(resource);
            return !entry.isFree() && entry.isAllocatedBy(client);
        }
    }

    @Override
    public Map<String, Set<String>> getAllocations() {
        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            return reservationPool.getAllocations();
        }
    }

    @Override
    public Set<String> getAllocationsByName(String name) {
        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            return reservationPool.getAllocationsByName(name);
        }
    }

    @Override
    public void preparationSuccessful(@Nonnull Module module,
                                      @Nonnull Client client,
                                      @Nonnull Set<String> resources) {
        requireNonNull(module, "module");
        requireNonNull(client, "client");
        requireNonNull(resources, "resources");

        WhiteBoardKt.getKernelExecutor().submit(new AllocatorTask(
                reservationPool,
                deferredAllocations,
                allocationAdvisor,
                new CheckAllocationsPrepared(client, resources)));
    }
}
