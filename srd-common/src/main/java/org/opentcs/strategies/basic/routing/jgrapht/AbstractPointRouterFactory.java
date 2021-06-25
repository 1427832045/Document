/**
 * Copyright (c) The openTCS Authors.
 * <p>
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.strategies.basic.routing.jgrapht;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nonnull;

import com.seer.srd.model.Path;
import com.seer.srd.model.Point;
import com.seer.srd.route.service.PlantModelService;
import org.jgrapht.Graph;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.strategies.basic.routing.PointRouter;
import org.opentcs.strategies.basic.routing.PointRouterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates {@link PointRouter} instances with algorithm implementations created by subclasses.
 *
 * @author Stefan Walter (Fraunhofer IML)
 */
public abstract class AbstractPointRouterFactory implements PointRouterFactory {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractPointRouterFactory.class);
    /**
     * Maps the plant model to a graph.
     */
    private final ModelGraphMapper mapper;

    public AbstractPointRouterFactory(@Nonnull ModelGraphMapper mapper) {
        this.mapper = requireNonNull(mapper, "mapper");
    }

    @Override
    public PointRouter createPointRouter(Vehicle vehicle) {
        requireNonNull(vehicle, "vehicle");

        long timeStampBefore = System.currentTimeMillis();

        Collection<Point> points = PlantModelService.INSTANCE.getPlantModel().getPoints().values();
        Graph<String, ModelEdge> graph = mapper.translateModel(points,
                PlantModelService.INSTANCE.getPlantModel().getPaths().values(),
                vehicle);

        PointRouter router = new ShortestPathPointRouter(createShortestPathAlgorithm(graph), points);
        // Make a single request for a route from one point to a different one to make sure the
        // point router is primed. (Some implementations are initialized lazily.)
        if (points.size() >= 2) {
            Iterator<Point> pointIter = points.iterator();
            router.getRouteSteps(pointIter.next(), pointIter.next());
        }

        LOG.debug("Created point router for {} in {} milliseconds.",
                vehicle.getName(),
                System.currentTimeMillis() - timeStampBefore);

        return router;
    }

    @Override
    public PointRouter createPointRouter(Vehicle vehicle, List<Path> exceptPaths) {
        requireNonNull(vehicle, "vehicle");

        long timeStampBefore = System.currentTimeMillis();

        Collection<Point> points = PlantModelService.INSTANCE.getPlantModel().getPoints().values();
        Graph<String, ModelEdge> graph = mapper.translateModel(points,
                PlantModelService.INSTANCE.getPlantModel().getPaths().values(),
                vehicle);

        // 将不能走的路干掉
        exceptPaths.forEach(path -> graph.removeEdge(path.getSourcePoint(), path.getDestinationPoint()));

        PointRouter router = new ShortestPathPointRouter(createShortestPathAlgorithm(graph), points);
        // Make a single request for a route from one point to a different one to make sure the
        // point router is primed. (Some implementations are initialized lazily.)
        if (points.size() >= 2) {
            Iterator<Point> pointIter = points.iterator();
            router.getRouteSteps(pointIter.next(), pointIter.next());
        }

        LOG.debug("Created special point router for {} in {} milliseconds.",
                vehicle.getName(),
                System.currentTimeMillis() - timeStampBefore);

        return router;
    }

    /**
     * Returns a shortest path algorithm implementation working on the given graph.
     *
     * @param graph The graph.
     * @return A shortest path algorithm implementation working on the given graph.
     */
    protected abstract ShortestPathAlgorithm<String, ModelEdge> createShortestPathAlgorithm(
            Graph<String, ModelEdge> graph);
}
