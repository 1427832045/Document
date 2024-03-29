package org.opentcs.strategies.basic.routing;

import com.seer.srd.model.Point;
import org.opentcs.data.order.Step;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Computes routes between points.
 *
 * @author Stefan Walter (Fraunhofer IML)
 */
public interface PointRouter {

    /**
     * A constant for marking the costs for a route as infinite.
     */
    long INFINITE_COSTS = Long.MAX_VALUE;
    /**
     * A constant for high routing costs (to be used for instance as a fallback value).
     */
    long HIGH_COSTS = INFINITE_COSTS - 1;

    /**
     * Returns a list of route steps to travel from a given source point to a given destination point.
     *
     * @param srcPoint  The source point.
     * @param destPoint The destination point.
     * @return A list of steps in the order they are to be travelled from the source point to the
     * destination point.
     * The returned list does not include a step for the source point.
     * If source point and destination point are identical, the returned list will be empty.
     * If no route exists, <code>null</code> will be returned.
     */
    List<Step> getRouteSteps(Point srcPoint, Point destPoint);

    /**
     * Returns the costs for travelling the shortest route from one point to another.
     *
     * @param srcPointName  The starting point reference.
     * @param destPointName The destination point reference.
     * @return The costs for travelling the shortest route from the starting point to the destination
     * point.
     * If no route exists, {@link #INFINITE_COSTS INFINITE_COSTS} will be returned.
     */
    long getCosts(String srcPointName, String destPointName);

    /**
     * Returns the costs for travelling the shortest route from one point to another.
     *
     * @param srcPoint  The starting point.
     * @param destPoint The destination point.
     * @return The costs for travelling the shortest route from the starting point to the destination
     * point.
     * If no route exists, {@link #INFINITE_COSTS INFINITE_COSTS} will be returned.
     */
    default long getCosts(Point srcPoint, Point destPoint) {
        requireNonNull(srcPoint, "srcPoint");
        requireNonNull(destPoint, "destPoint");

        return getCosts(srcPoint.getName(), destPoint.getName());
    }
}
