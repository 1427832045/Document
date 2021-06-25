package org.opentcs.strategies.basic.routing.jgrapht;

import com.seer.srd.model.Point;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.data.order.Step;
import org.opentcs.strategies.basic.routing.PointRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static java.util.Objects.requireNonNull;

/**
 * Computes routes between points using a JGraphT-based shortest path algorithm.
 * <p>
 * <em>Note that this implementation does not integrate static routes.</em>
 * </p>
 *
 * @author Stefan Walter (Fraunhofer IML)
 */
public class ShortestPathPointRouter implements PointRouter {

    /**
     * This class's logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ShortestPathPointRouter.class);

    private final ShortestPathAlgorithm<String, ModelEdge> algo;

    private final Map<String, Point> points = new HashMap<>();

    public ShortestPathPointRouter(ShortestPathAlgorithm<String, ModelEdge> algo,
                                   Collection<Point> points) {
        this.algo = requireNonNull(algo, "algo");
        requireNonNull(points, "points");

        for (Point point : points) {
            this.points.put(point.getName(), point);
        }

    }

    @Override
    public List<Step> getRouteSteps(Point srcPoint, Point destPoint) {
        requireNonNull(srcPoint, "srcPoint");
        requireNonNull(destPoint, "destPoint");

        long timeBefore = System.currentTimeMillis();
        if (Objects.equals(srcPoint.getName(), destPoint.getName())) {
            return new ArrayList<>();
        }

        GraphPath<String, ModelEdge> graphPath = algo.getPath(srcPoint.getName(), destPoint.getName());
        if (graphPath == null) {
            return null;
        }

        List<Step> result = translateToSteps(graphPath);

        LOG.debug("Looking up route from {} to {} took {} milliseconds.",
                srcPoint.getName(),
                destPoint.getName(),
                System.currentTimeMillis() - timeBefore);

        return result;
    }

    @Override
    public long getCosts(String srcPointName, String destPointName) {
        requireNonNull(srcPointName, "srcPointName");
        requireNonNull(destPointName, "destPointName");

        if (Objects.equals(srcPointName, destPointName)) return 0;

        GraphPath<String, ModelEdge> graphPath = algo.getPath(srcPointName, destPointName);
        if (graphPath == null) return INFINITE_COSTS;

        return (long) graphPath.getWeight();
    }

    private List<Step> translateToSteps(GraphPath<String, ModelEdge> graphPath) {
        List<ModelEdge> edges = graphPath.getEdgeList();
        List<Step> result = new ArrayList<>(edges.size());

        int routeIndex = 0;
        for (ModelEdge edge : edges) {
            String sourcePoint = graphPath.getGraph().getEdgeSource(edge);
            Point destPoint = points.get(graphPath.getGraph().getEdgeTarget(edge));

            result.add(new Step(edge.getModelPath().getName(), sourcePoint, destPoint.getName(), orientation(edge, sourcePoint), routeIndex, true));
            routeIndex++;
        }

        return result;
    }

    private Vehicle.Orientation orientation(ModelEdge edge, String graphSourcePoint) {
        return Objects.equals(edge.getModelPath().getSourcePoint(), graphSourcePoint)
                ? Vehicle.Orientation.FORWARD
                : Vehicle.Orientation.BACKWARD;
    }
}
