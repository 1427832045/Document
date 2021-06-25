package org.opentcs.strategies.basic.dispatching.priorization;

import com.google.common.collect.Lists;

import java.util.Comparator;
import java.util.Map;
import javax.inject.Inject;

import com.seer.srd.route.RouteConfigKt;
import org.opentcs.strategies.basic.dispatching.AssignmentCandidate;
import org.opentcs.strategies.basic.dispatching.priorization.candidate.CandidateComparatorByOrderAge;
import org.opentcs.strategies.basic.dispatching.priorization.candidate.CandidateComparatorByOrderName;

import static org.opentcs.util.Assertions.checkArgument;

/**
 * A composite of all configured transport order candidate comparators.
 */
public class CompositeOrderCandidateComparator implements Comparator<AssignmentCandidate> {

    /**
     * A comparator composed of all configured comparators, in the configured order.
     */
    private final Comparator<AssignmentCandidate> compositeComparator;

    @Inject
    public CompositeOrderCandidateComparator(
            Map<String, Comparator<AssignmentCandidate>> availableComparators) {
        // At the end, if all other comparators failed to see a difference, compare by age.
        // As the age of two distinct transport orders may still be the same, finally compare by name.
        // Add configured comparators before these two.
        Comparator<AssignmentCandidate> composite
                = new CandidateComparatorByOrderAge().thenComparing(new CandidateComparatorByOrderName());

        for (String priorityKey : Lists.reverse(RouteConfigKt.getRouteConfig().getDispatcher().getOrderCandidatePriorities())) {
            Comparator<AssignmentCandidate> configuredComparator = availableComparators.get(priorityKey);
            checkArgument(configuredComparator != null,
                    "Unknown order candidate priority key: %s",
                    priorityKey);
            composite = configuredComparator.thenComparing(composite);
        }
        this.compositeComparator = composite;
    }

    @Override
    public int compare(AssignmentCandidate o1, AssignmentCandidate o2) {
        return compositeComparator.compare(o1, o2);
    }

}