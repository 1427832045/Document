package org.opentcs.strategies.basic.scheduling.modules;

import com.seer.srd.route.WhiteBoardKt;
import com.seer.srd.route.service.PlantModelService;
import org.jetbrains.annotations.NotNull;
import org.opentcs.components.kernel.Scheduler;
import com.seer.srd.model.Block;
import com.seer.srd.model.BlockType;
import org.opentcs.strategies.basic.scheduling.ReservationPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Checks if the resources a client may allocate are part of a
 * {@link BlockType#SINGLE_VEHICLE_ONLY} block and whether the expanded resources are all available
 * to the client.
 */
public class SingleVehicleBlockModule implements Scheduler.Module {

    private static final Logger LOG = LoggerFactory.getLogger(SingleVehicleBlockModule.class);

    private final ReservationPool reservationPool;

    private boolean initialized;

    @Inject
    public SingleVehicleBlockModule(@Nonnull ReservationPool reservationPool) {
        this.reservationPool = requireNonNull(reservationPool, "reservationPool");
    }

    @Override
    public void initialize() {
        if (isInitialized()) {
            return;
        }

        initialized = true;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void terminate() {
        if (!isInitialized()) {
            return;
        }

        initialized = false;
    }

    @Override
    public void claim(@NotNull Scheduler.Client client, @NotNull List<Set<String>> claim) {
    }

    @Override
    public void unclaim(@NotNull Scheduler.Client client) {
    }

    @Override
    public void setAllocationState(@NotNull Scheduler.Client client, @NotNull Set<String> alloc, @NotNull List<Set<String>> remainingClaim) {
    }

    @Override
    public boolean mayAllocate(@NotNull Scheduler.Client client, @NotNull Set<String> resources) {
        requireNonNull(client, "client");
        requireNonNull(resources, "resources");

        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            Set<Block> blocks = filterBlocksContainingResources(resources);

            if (blocks.isEmpty()) {
                LOG.debug("{}: No blocks to be checked, allocation allowed.", client.getId());
                return true;
            }

            Set<String> resourcesExpanded = expandResources(resources);
            resourcesExpanded = filterRelevantResources(resourcesExpanded, blocks);

            LOG.debug("{}: Checking resource availability: {}", client.getId(), resources);
            if (!reservationPool.resourcesAvailableForUser(resourcesExpanded, client)) {
                LOG.debug("{}: Resources unavailable: {}.", client.getId(), resources);
                return false;
            }

            LOG.debug("{}: Resources available, allocation allowed.", client.getId());
            return true;
        }
    }

    @Override
    public void prepareAllocation(@NotNull Scheduler.Client client, @NotNull Set<String> resources) {
    }

    @Override
    public boolean hasPreparedAllocation(@NotNull Scheduler.Client client, @NotNull Set<String> resources) {
        return true;
    }

    @Override
    public void allocationReleased(@NotNull Scheduler.Client client, @NotNull Set<String> resources) {
    }

    private Set<Block> filterBlocksContainingResources(Set<String> resources) {
        Set<Block> result = new HashSet<>();
        Set<Block> blocks = PlantModelService.INSTANCE.getPlantModel().getBlocks().values()
                .stream().filter(block -> block.getType() == BlockType.SINGLE_VEHICLE_ONLY).collect(Collectors.toSet());
        for (String resource : resources) {
            for (Block block : blocks) {
                if (block.getMembers().contains(resource)) {
                    result.add(block);
                }
            }
        }
        return result;
    }

    private Set<String> filterRelevantResources(Set<String> resources, Set<Block> blocks) {
        Set<String> blockResources = blocks.stream()
                .flatMap(block -> block.getMembers().stream())
                .collect(Collectors.toSet());

        return resources.stream()
                .filter(blockResources::contains)
                .collect(Collectors.toSet());
    }

    /**
     * Returns the given set of resources after expansion (by resolution of blocks, for instance) by
     * the kernel.
     *
     * @param resources The set of resources to be expanded.
     * @return The given set of resources after expansion (by resolution of
     * blocks, for instance) by the kernel.
     */
    private Set<String> expandResources(Set<String> resources) {
        requireNonNull(resources, "resources");
        // Build a set of references
        Set<String> refs = new HashSet<>(resources);
        // Let the kernel expand the resources for us.
        Set<String> result = PlantModelService.INSTANCE.expandResources(refs);
        LOG.debug("Set {} expanded to {}", resources, result);
        return result;
    }

}
