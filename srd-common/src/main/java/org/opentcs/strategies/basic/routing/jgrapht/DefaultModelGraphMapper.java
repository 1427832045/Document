package org.opentcs.strategies.basic.routing.jgrapht;

import java.util.Collection;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nonnull;
import javax.inject.Inject;

import com.seer.srd.model.Point;
import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.ShortestPathConfiguration;
import org.jgrapht.Graph;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import com.seer.srd.model.Path;
import com.seer.srd.vehicle.Vehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mapper to translate a collection of points and paths into a weighted graph.
 */
public class DefaultModelGraphMapper implements ModelGraphMapper {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultModelGraphMapper.class);

    /**
     * Computes the weight of single edges in the graph.
     */
    private final EdgeEvaluator edgeEvaluator;

    @Inject
    public DefaultModelGraphMapper(@Nonnull EdgeEvaluator edgeEvaluator) {
        this.edgeEvaluator = requireNonNull(edgeEvaluator, "edgeEvaluator");
    }

    @Override
    public Graph<String, ModelEdge> translateModel(Collection<Point> points,
                                                   Collection<Path> paths,
                                                   Vehicle vehicle) {
        requireNonNull(points, "points");
        requireNonNull(paths, "paths");
        requireNonNull(vehicle, "vehicle");

        Graph<String, ModelEdge> graph = new DirectedWeightedMultigraph<>(ModelEdge.class);

        for (Point point : points) {
            graph.addVertex(point.getName());
        }

        ShortestPathConfiguration configuration = RouteConfigKt.getRouteConfig().getShortestPath();

        boolean allowNegativeEdgeWeights = configuration.getAlgorithm().isHandlingNegativeCosts();

        for (Path path : paths) {

            if (shouldAddForwardEdge(path, vehicle)) {
                ModelEdge edge = new ModelEdge(path, false);
                double weight = edgeEvaluator.computeWeight(edge, vehicle);

                if (weight < 0 && !allowNegativeEdgeWeights) {
                    LOG.warn("Edge {} with weight {} ignored. Algorithm {} cannot handle negative weights.",
                            edge,
                            weight,
                            configuration.getAlgorithm().name());
                } else {
                    graph.addEdge(path.getSourcePoint(), path.getDestinationPoint(), edge);
                    graph.setEdgeWeight(edge, weight);
                }
            }

            if (shouldAddReverseEdge(path, vehicle)) {
                ModelEdge edge = new ModelEdge(path, true);
                double weight = edgeEvaluator.computeWeight(edge, vehicle);

                if (weight < 0 && !allowNegativeEdgeWeights) {
                    LOG.warn("Edge {} with weight {} ignored. Algorithm {} cannot handle negative weights.",
                            edge,
                            weight,
                            configuration.getAlgorithm().name());
                } else {
                    graph.addEdge(path.getDestinationPoint(), path.getSourcePoint(), edge);
                    graph.setEdgeWeight(edge, weight);
                }
            }

        }

        return graph;
    }

    /**
     * Returns <code>true</code> if and only if the graph should contain an edge from the source
     * of the path to its destination for the given vehicle.
     *
     * @param path    The path
     * @param vehicle The vehicle
     * @return <code>true</code> if and only if the graph should contain the edge
     */
    protected boolean shouldAddForwardEdge(Path path, Vehicle vehicle) {
        return path.isNavigableForward();
    }

    /**
     * Returns <code>true</code> if and only if the graph should contain an edge from the destination
     * of the path to its source for the given vehicle.
     *
     * @param path    The path
     * @param vehicle The vehicle
     * @return <code>true</code> if and only if the graph should contain the edge
     */
    protected boolean shouldAddReverseEdge(Path path, Vehicle vehicle) {
        return path.isNavigableReverse();
    }
}
