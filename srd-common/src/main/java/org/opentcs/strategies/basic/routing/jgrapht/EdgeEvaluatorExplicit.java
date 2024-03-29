/**
 * Copyright (c) The openTCS Authors.
 *
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.strategies.basic.routing.jgrapht;

import com.seer.srd.model.Path;
import com.seer.srd.vehicle.Vehicle;

/**
 * Uses an edge's explicit routing cost as its weight.
 *
 * @author Stefan Walter (Fraunhofer IML)
 * @deprecated {@link Path#getRoutingCost()} is deprecated.
 */
@Deprecated
public class EdgeEvaluatorExplicit
    implements EdgeEvaluator {

  public EdgeEvaluatorExplicit() {
  }

  @Override
  public double computeWeight(ModelEdge edge, Vehicle vehicle) {
    return edge.getModelPath().getRoutingCost();
  }
}
