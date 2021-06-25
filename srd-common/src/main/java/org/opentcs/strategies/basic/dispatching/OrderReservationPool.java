package org.opentcs.strategies.basic.dispatching;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Stores reservations of orders for vehicles.
 */
public class OrderReservationPool {

    /**
     * Reservations of orders for vehicles.
     * TransportOrder -> Vehicle
     */
    private final Map<String, String> orderReservations = Collections.synchronizedMap(new HashMap<>());

    @Inject
    public OrderReservationPool() {
    }

    public void clear() {
        orderReservations.clear();
    }

    /**
     * Checks whether there is a reservation of the given transport order for any vehicle.
     */
    public boolean isReserved(@Nonnull String orderName) {
        return orderReservations.containsKey(orderName);
    }

    public void addReservation(@Nonnull String orderName, @Nonnull String vehicleName) {
        orderReservations.put(orderName, vehicleName);
    }

    public void removeReservation(@Nonnull String orderName) {
        orderReservations.remove(orderName);
    }

    public void removeReservations(@Nonnull String vehicleName) {
        orderReservations.values().removeIf(vehicleName::equals);
    }

    public List<String> findReservations(@Nonnull String vehicleName) {
        return orderReservations.entrySet().stream()
                .filter(entry -> vehicleName.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

}
