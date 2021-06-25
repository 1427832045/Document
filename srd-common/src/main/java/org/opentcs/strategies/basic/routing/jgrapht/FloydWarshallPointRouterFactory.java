package org.opentcs.strategies.basic.routing.jgrapht;

import javax.annotation.Nonnull;
import javax.inject.Inject;

import org.jgrapht.Graph;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.shortestpath.FloydWarshallShortestPaths;
import org.opentcs.strategies.basic.routing.PointRouter;

/**
 * Creates {@link PointRouter} instances based on the Floyd-Warshall algorithm.
 *
 * @author Stefan Walter (Fraunhofer IML)
 */
public class FloydWarshallPointRouterFactory extends AbstractPointRouterFactory {

    @Inject
    public FloydWarshallPointRouterFactory(@Nonnull ModelGraphMapper mapper) {
        super(mapper);
    }

    @Override
    protected ShortestPathAlgorithm<String, ModelEdge> createShortestPathAlgorithm(
            Graph<String, ModelEdge> graph) {
        return new FloydWarshallShortestPaths<>(graph);
    }

}
