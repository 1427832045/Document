package org.opentcs.strategies.basic.routing.jgrapht;

import javax.annotation.Nonnull;
import javax.inject.Inject;

import org.jgrapht.Graph;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.shortestpath.BellmanFordShortestPath;
import org.opentcs.strategies.basic.routing.PointRouter;

/**
 * Creates {@link PointRouter} instances based on the Bellman-Ford algorithm.
 */
public class BellmanFordPointRouterFactory extends AbstractPointRouterFactory {

    @Inject
    public BellmanFordPointRouterFactory(@Nonnull ModelGraphMapper mapper) {
        super(mapper);
    }

    @Override
    protected ShortestPathAlgorithm<String, ModelEdge> createShortestPathAlgorithm(
            Graph<String, ModelEdge> graph) {
        return new BellmanFordShortestPath<>(graph);
    }

}
