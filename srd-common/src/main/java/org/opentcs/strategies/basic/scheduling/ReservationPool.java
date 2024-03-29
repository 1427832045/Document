package org.opentcs.strategies.basic.scheduling;

import com.seer.srd.model.Point;
import com.seer.srd.route.service.PlantModelService;
import org.opentcs.components.kernel.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * @author Stefan Walter (Fraunhofer IML)
 */
public class ReservationPool {

    /**
     * This class's Logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ReservationPool.class);
    /**
     * <code>ReservationEntry</code> instances for each <code>TCSResource</code>.
     */
    private final Map<String, ReservationEntry> reservations = new HashMap<>();

    /**
     * Creates a new instance.
     */
    @Inject
    public ReservationPool() {
    }

    /**
     * Returns a reservation entry for the given resource.
     *
     * @param resource The resource for which to return the reservation entry.
     * @return The reservation entry for the given resource.
     */
    public ReservationEntry getReservationEntry(String resource) {
        requireNonNull(resource, "resource");

        ReservationEntry entry = reservations.get(resource);
        if (entry == null) {
            entry = new ReservationEntry(resource);
            reservations.put(resource, entry);
        }
        return entry;
    }

    /**
     * Returns all resources allocated by the given client.
     *
     * @param client The client for which to return all allocated resources.
     * @return All resources allocated by the given client.
     */
    public Set<String> allocatedResources(Scheduler.Client client) {
        requireNonNull(client, "client");

        return reservations.entrySet().stream()
                .filter(entry -> entry.getValue().isAllocatedBy(client))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /**
     * Checks if all resources in the given set of resources are be available for the given client.
     *
     * @param resources The set of resources to be checked.
     * @param client    The client for which to check.
     * @return <code>true</code> if, and only if, all resources in the given set
     * are available for the given client.
     */
    public boolean resourcesAvailableForUser(Set<String> resources, Scheduler.Client client) {
        requireNonNull(resources, "resources");
        requireNonNull(client, "client");

        for (String curResource : resources) {
            // Check if the resource is available.
            ReservationEntry entry = getReservationEntry(curResource);
            if (!entry.isFree() && !entry.isAllocatedBy(client)) {
                LOG.debug("{}: Resource unavailable: {}", client.getId(), entry.getResource());
                String pointName = entry.getResource();
                Collection<Point> points = PlantModelService.INSTANCE.getPlantModel().getPoints().values();
                for (Point p: points) {
                    if (p.getName() == pointName) {
                        String occupyingVehicle = p.getOccupyingVehicle();
                        LOG.debug("Unavailable resource: {}, is occupy by {}", entry.getResource(), occupyingVehicle);
                    }
                }
                return false;
            }
        }
        return true;
    }

    /**
     * Returns a set of resources that is a subset of the given set of resources and is reserved/could
     * be released by the given client.
     *
     * @param resources The set of resources to be filtered for resources that could be released.
     * @param client    The client that should be able to release the returned resources.
     * @return A set of resources that is a subset of the given set of resources and is reserved/could
     * be released by the given client.
     */
    private Set<String> getFreeableResources(Set<String> resources, Scheduler.Client client) {
        // Make sure we're freeing only resources that are allocated by us.
        final Set<String> freeableResources = new HashSet<>();
        for (String curRes : resources) {
            ReservationEntry entry = getReservationEntry(curRes);
            if (!entry.isAllocatedBy(client)) {
                LOG.warn("{}: Freed resource not reserved: {}, entry: {}", client.getId(), curRes, entry);
            } else {
                freeableResources.add(curRes);
            }
        }
        return freeableResources;
    }

    public void free(Scheduler.Client client, Set<String> resources) {
        requireNonNull(client, "client");
        requireNonNull(resources, "resources");

        LOG.debug("{}: Releasing resources: {}", client.getId(), resources);
        Set<String> freeableResources = getFreeableResources(resources, client);
        for (String curResource : freeableResources) {
            getReservationEntry(curResource).free();
        }
    }

    public void freeAll(Scheduler.Client client) {
        requireNonNull(client, "client");

        reservations.values().stream()
                .filter(reservationEntry -> reservationEntry.isAllocatedBy(client))
                .forEach(ReservationEntry::freeCompletely);
    }

    public void freeAllExcept(Scheduler.Client client, Set<String> exceptResources) {
        requireNonNull(client, "client");
        requireNonNull(exceptResources, "exceptResources");

        reservations.values().stream()
                .filter(reservationEntry -> reservationEntry.isAllocatedBy(client))
                .filter(reservationEntry -> !exceptResources.contains(reservationEntry.getResource()))
                .forEach(ReservationEntry::freeCompletely);

        // 将当前占用的资源，设置为只引用一次。
        reservations.values().stream()
                .filter(reservationEntry -> reservationEntry.isAllocatedBy(client))
                .forEach(ReservationEntry::freeToOneReference);
    }

    public Map<String, Set<String>> getAllocations() {
        final Map<String, Set<String>> result = new HashMap<>();
        for (Map.Entry<String, ReservationEntry> curEntry : reservations.entrySet()) {
            final String curResource = curEntry.getKey();
            final Scheduler.Client curUser = curEntry.getValue().getClient();
            if (curUser != null) {
                Set<String> userResources = result.get(curUser.getId());
                if (userResources == null) {
                    userResources = new HashSet<>();
                }
                userResources.add(curResource);
                result.put(curUser.getId(), userResources);
            }
        }
        return result;
    }

    public Set<String> getAllocationsByName(String name) {
        if (name == null) return new HashSet<>();
        return reservations.entrySet()
            .stream()
            .filter(entry -> name.equals(entry.getValue().getClient() == null ? null : entry.getValue().getClient().getId()))
            .map(entry -> entry.getKey())
            .collect(Collectors.toSet());
    }

    public void clear() {
        reservations.clear();
    }
}
