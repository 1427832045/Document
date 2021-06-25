package org.opentcs.strategies.basic.scheduling.modules;

import com.seer.srd.route.WhiteBoardKt;
import com.seer.srd.route.service.PlantModelService;
import org.jetbrains.annotations.NotNull;
import org.opentcs.components.kernel.Scheduler;
import com.seer.srd.model.Block;
import com.seer.srd.model.BlockType;
import com.seer.srd.model.Path;
import org.opentcs.strategies.basic.scheduling.ReservationPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static org.opentcs.components.kernel.Scheduler.PROPKEY_BLOCK_ENTRY_DIRECTION;

/**
 * Checks if the resources a client may allocate are part of a
 * {@link BlockType#SAME_DIRECTION_ONLY} block and whether the client is allowed to drive along
 * the block in the requested direction.
 */
public class SameDirectionBlockModule implements Scheduler.Module {

    private static final Logger LOG = LoggerFactory.getLogger(SameDirectionBlockModule.class);

    private final ReservationPool reservationPool;

    /**
     * The permissions for all {@link BlockType#SAME_DIRECTION_ONLY} blocks in a plant model.
     */
    private final Map<Block, BlockPermission> permissions = new HashMap<>();

    private boolean initialized;

    @Inject
    public SameDirectionBlockModule(@Nonnull ReservationPool reservationPool) {
        this.reservationPool = requireNonNull(reservationPool, "reservationPool");
    }

    @Override
    public void initialize() {
        if (isInitialized()) {
            return;
        }

        Collection<Block> blocks = PlantModelService.INSTANCE.getPlantModel().getBlocks().values();
        for (Block block : blocks) {
            if (block.getType() == BlockType.SAME_DIRECTION_ONLY) {
                permissions.put(block, new BlockPermission(block));
            }
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

        permissions.clear();

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
            // Other modules may prevented the last allocation, discard any previous requests.
            discardPreviousRequests();

            Set<Block> blocks = filterBlocksContainingResources(resources);
            if (blocks.isEmpty()) {
                LOG.debug("{}: No blocks to be checked, allocation allowed.", client.getId());
                return true;
            }

            Path path = selectPath(resources);
            if (path == null) {
                // If there's no path in the requested resources the vehicle won't move and already has
                // permission to be in the block(s).
                LOG.debug("{}: No path in resources, allocation allowed.", client.getId());
                return true;
            }

            LOG.debug("{}: Checking resource availability: {}", client.getId(), resources);
            if (!checkBlockEntryPermissions(client,
                    blocks,
                    path.getProperties().getOrDefault(PROPKEY_BLOCK_ENTRY_DIRECTION,
                            path.getName()))) {
                LOG.debug("{}: Resources unavailable.", client.getId());
                return false;
            }

            LOG.debug("{}: Resources available, allocation allowed.", client.getId());
            return true;
        }
    }

    @Override
    public void prepareAllocation(@NotNull Scheduler.Client client, @NotNull Set<String> resources) {
        permissions.values().forEach(BlockPermission::permitPendingRequests);
    }

    @Override
    public boolean hasPreparedAllocation(@NotNull Scheduler.Client client, @NotNull Set<String> resources) {
        return permissions.values().stream()
                .noneMatch(BlockPermission::hasPendingRequests);
    }

    @Override
    public void allocationReleased(@NotNull Scheduler.Client client, @NotNull Set<String> resources) {
        requireNonNull(client, "client");
        requireNonNull(resources, "resources");

        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            for (Map.Entry<Block, BlockPermission> entry : permissions.entrySet()) {
                Block block = entry.getKey();
                BlockPermission permission = entry.getValue();

                if (!permission.isPermissionGranted(client)) {
                    continue;
                }

                if (blockResourcesAllocatedByClient(block, client)) {
                    continue;
                }

                // The client released resources and does no longer hold any resources of this block.
                // We don't need permissions any more.
                permission.removePermissionFor(client);
            }
        }
    }

    private void discardPreviousRequests() {
        LOG.debug("Discarding all pending requests...");
        permissions.values().forEach(BlockPermission::clearPendingRequests);
    }

    private Set<Block> filterBlocksContainingResources(Set<String> resources) {
        Set<Block> result = new HashSet<>();
        Set<Block> blocks = PlantModelService.INSTANCE.getPlantModel().getBlocks().values()
                .stream().filter(block -> block.getType() == BlockType.SAME_DIRECTION_ONLY).collect(Collectors.toSet());
        for (String resource : resources) {
            for (Block block : blocks) {
                if (block.getMembers().contains(resource)) {
                    result.add(block);
                }
            }
        }
        return result;
    }

    @Nullable
    private Path selectPath(Set<String> resources) {
        for (String resource : resources) {
            Path path = PlantModelService.INSTANCE.getPathIfNameIs(resource);
            if (path != null) return path;
        }

        return null;
    }

    private boolean checkBlockEntryPermissions(Scheduler.Client client,
                                               Set<Block> blocks,
                                               String entryDirection) {
        LOG.debug("{}: Checking entry permissions for blocks '{}' with entry direction '{}'.",
                client.getId(),
                entryDirection);
        boolean entryPermissible = true;
        for (Block block : blocks) {
            entryPermissible &= permissions.get(block).enqueueRequest(client, entryDirection);
        }

        return entryPermissible;
    }

    private boolean blockResourcesAllocatedByClient(Block block, Scheduler.Client client) {
        Set<Block> clientBlocks
                = filterBlocksContainingResources(reservationPool.allocatedResources(client)
        );
        return clientBlocks.contains(block);
    }

    /**
     * Manages the clients that are permitted to drive along a block by considering the direction
     * clients request to enter the block.
     */
    private static class BlockPermission {

        /**
         * The block to manage permissions for.
         */
        private final Block block;
        /**
         * The clients permitted to drive along the block.
         */
        private final Set<Scheduler.Client> clients = new HashSet<>();
        /**
         * The direction vehicles are allowed to enter the block.
         */
        @Nullable
        private String entryDirection;
        /**
         * The queue of pending permission requests.
         */
        private final Queue<PermissionRequest> pendingRequests = new LinkedList<>();

        public BlockPermission(Block block) {
            this.block = requireNonNull(block, "block");
        }

        public void permitPendingRequests() {
            while (hasPendingRequests()) {
                PermissionRequest request = pendingRequests.poll();

                assert request != null;
                if (clientAlreadyInBlock(request.getClient())) {
                    LOG.debug("Permission for block {} already granted to {}.",
                            block.getName(),
                            request.getClient().getId());
                } else if (entryPermissible(request.getEntryDirection())) {
                    clients.add(request.getClient());
                    this.entryDirection = request.getEntryDirection();
                    LOG.debug("Permission for block {} granted to {} (entryDirection={}).",
                            block.getName(),
                            request.getClient().getId(),
                            request.getEntryDirection());
                }
            }
        }

        public boolean enqueueRequest(Scheduler.Client client, String entryDirection) {
            if (clientAlreadyInBlock(client)
                    || entryPermissible(entryDirection)) {
                LOG.debug("Enqueuing permission request for block {} to {} with entry direction '{}'.",
                        block.getName(),
                        client.getId(),
                        entryDirection);
                pendingRequests.add(new PermissionRequest(client, entryDirection));
                return true;
            }

            LOG.debug("Client {} not permissible to block {} with entry direction '{}' (!= '{}').",
                    client.getId(),
                    block.getName(),
                    entryDirection,
                    this.entryDirection);
            return false;
        }

        public void clearPendingRequests() {
            pendingRequests.clear();
        }

        public void removePermissionFor(Scheduler.Client client) {
            clients.remove(client);

            if (clients.isEmpty()) {
                entryDirection = null;
            }
        }

        public boolean isPermissionGranted(Scheduler.Client client) {
            return clients.contains(client);
        }

        private boolean hasPendingRequests() {
            return !pendingRequests.isEmpty();
        }

        private boolean clientAlreadyInBlock(Scheduler.Client client) {
            return isPermissionGranted(client);
        }

        private boolean entryPermissible(String entryDirection) {
            return this.entryDirection == null
                    || Objects.equals(this.entryDirection, entryDirection);
        }
    }

    private static class PermissionRequest {

        /**
         * The requesting client.
         */
        private final Scheduler.Client client;
        /**
         * The entry direction permission is requested for.
         */
        private final String entryDirection;

        /**
         * Creates a new instance.
         *
         * @param client         The requesting client.
         * @param entryDirection The entry direction permission is requested for.
         */
        public PermissionRequest(Scheduler.Client client, String entryDirection) {
            this.client = requireNonNull(client, "client");
            this.entryDirection = requireNonNull(entryDirection, "entryDirection");
        }

        public Scheduler.Client getClient() {
            return client;
        }

        public String getEntryDirection() {
            return entryDirection;
        }
    }
}
