/**
 * Copyright (c) The openTCS Authors.
 * <p>
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.strategies.basic.routing.jgrapht;

import javax.annotation.Nonnull;
import javax.inject.Inject;

import org.jgrapht.Graph;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.opentcs.strategies.basic.routing.PointRouter;

/**
 * Creates {@link PointRouter} instances based on the Dijkstra algorithm.
 *
 * @author Stefan Walter (Fraunhofer IML)
 */
public class DijkstraPointRouterFactory
        extends AbstractPointRouterFactory {

    @Inject
    public DijkstraPointRouterFactory(@Nonnull ModelGraphMapper mapper) {
        super(mapper);
    }

    @Override
    protected ShortestPathAlgorithm<String, ModelEdge> createShortestPathAlgorithm(
            Graph<String, ModelEdge> graph) {
        return new DijkstraShortestPath<>(graph);
    }

}
