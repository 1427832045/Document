/**
 * Copyright (c) The openTCS Authors.
 * <p>
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.strategies.basic.routing.jgrapht;

import com.seer.srd.model.Path;
import com.seer.srd.vehicle.Vehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Objects.requireNonNull;
import static org.opentcs.components.kernel.Router.*;

// import static org.opentcs.strategies.basic.routing.PointRouter.HIGH_COSTS;

/**
 * Uses an edge's explicit routing cost (given as a property value) as its weight.
 *
 * @author Stefan Walter (Fraunhofer IML)
 */
public class EdgeEvaluatorExplicitProperties
        implements EdgeEvaluator {

    /**
     * This class's logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(EdgeEvaluatorExplicitProperties.class);

    public EdgeEvaluatorExplicitProperties() {
    }

    @Override
    public double computeWeight(ModelEdge edge, Vehicle vehicle) {
        requireNonNull(edge, "edge");
        requireNonNull(vehicle, "vehicle");

        String group = extractVehicleGroup(vehicle);

        if (edge.isTravellingReverse()) {
            return parseCosts(extractRoutingCostString(edge.getModelPath(),
                    PROPKEY_ROUTING_COST_REVERSE + group));
        } else {
            return parseCosts(extractRoutingCostString(edge.getModelPath(),
                    PROPKEY_ROUTING_COST_FORWARD + group));
        }
    }

    private String extractVehicleGroup(Vehicle vehicle) {
        String group = vehicle.getProperties().get(PROPKEY_ROUTING_GROUP);

        return group == null ? "" : group;
    }

    private String extractRoutingCostString(Path path, String propertyKey) {
        String propVal = path.getProperties().get(propertyKey);

        if (propVal == null) {
            // LOG.debug("No routing cost property value for key '{}' in path '{}'", propertyKey, path);
            return Long.toString(0);
        }
        return propVal;
    }

    private long parseCosts(String costs) {
        try {
            return Long.parseLong(costs);
        } catch (NumberFormatException exc) {
            LOG.warn("Exception parsing routing cost value '{}'.", costs, exc);
            return 0;
        }
    }
}
