/**
 * Copyright (c) The openTCS Authors.
 * <p>
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.strategies.basic.routing.jgrapht;

import java.util.Collection;

import com.seer.srd.model.Point;
import org.jgrapht.Graph;
import com.seer.srd.model.Path;
import com.seer.srd.vehicle.Vehicle;

/**
 * Translates model data to weighted graphs.
 *
 * @author Stefan Walter (Fraunhofer IML)
 */
public interface ModelGraphMapper {

    /**
     * Translates the given points and paths to a weighted graph.
     *
     * @param points The points of the model.
     * @param paths The paths of the model.
     * @param vehicle The vehicle for which to build the graph.
     * @return A weighted graph representing the topology to be used for the given vehicle.
     */
    Graph<String, ModelEdge> translateModel(Collection<Point> points,
                                            Collection<Path> paths,
                                            Vehicle vehicle);
}
