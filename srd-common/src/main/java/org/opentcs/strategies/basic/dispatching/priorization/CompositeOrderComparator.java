package org.opentcs.strategies.basic.dispatching.priorization;

import com.google.common.collect.Lists;

import java.util.Comparator;
import java.util.Map;
import javax.inject.Inject;

import com.seer.srd.route.RouteConfigKt;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.strategies.basic.dispatching.priorization.transportorder.TransportOrderComparatorByAge;
import org.opentcs.strategies.basic.dispatching.priorization.transportorder.TransportOrderComparatorByName;

import static org.opentcs.util.Assertions.checkArgument;

/**
 * A composite of all configured transport order comparators.
 */
public class CompositeOrderComparator
        implements Comparator<TransportOrder> {

    /**
     * A comparator composed of all configured comparators, in the configured order.
     */
    private final Comparator<TransportOrder> compositeComparator;

    @Inject
    public CompositeOrderComparator(Map<String, Comparator<TransportOrder>> availableComparators) {
        // At the end, if all other comparators failed to see a difference, compare by age.
        // As the age of two distinct transport orders may still be the same, finally compare by name.
        // Add configured comparators before these two.
        Comparator<TransportOrder> composite
                = new TransportOrderComparatorByAge().thenComparing(new TransportOrderComparatorByName());

        for (String priorityKey : Lists.reverse(RouteConfigKt.getRouteConfig().getDispatcher().getOrderPriorities())) {
            Comparator<TransportOrder> configuredComparator = availableComparators.get(priorityKey);
            checkArgument(configuredComparator != null, "Unknown order priority key: %s", priorityKey);
            composite = configuredComparator.thenComparing(composite);
        }
        this.compositeComparator = composite;
    }

    @Override
    public int compare(TransportOrder o1, TransportOrder o2) {
        return compositeComparator.compare(o1, o2);
    }

}
