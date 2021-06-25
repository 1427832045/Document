/**
 * Copyright (c) The openTCS Authors.
 *
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.kernel.vehicles.synchronizer;

import java.util.Set;
import static java.util.Objects.requireNonNull;
import javax.annotation.Nonnull;
import javax.inject.Inject;
import org.opentcs.util.ExplainedBoolean;
import org.opentcs.drivers.vehicle.MovementCommand;
import org.opentcs.drivers.vehicle.synchronizer.*;

/**
 * Implements a basic device synchronizer to ensure the logic between devices and vehicles.
 *
 */
public class DummyDeviceSynchronizer 
    implements DeviceSynchronizer {
  
  /**
   * The Set of implementions of synchronizer request interfaces
   */
  private final Set<RequestBefore> reqsBefore;
  /**
   * The Set of implementions of synchronizer request interfaces
   */
  private final Set<QueryBefore> querysBefore;
  /**
   * The Set of implementions of synchronizer request interfaces
   */
  private final Set<RequestAtSend> reqsAtSend;
  /**
   * The Set of implementions of synchronizer request interfaces
   */
  private final Set<RequestWhile> reqsWhile;
  /**
   * The Set of implementions of synchronizer request interfaces
   */
  private final Set<QueryAtExecuted> querysAtExecuted;
  /**
   * The Set of implementions of synchronizer request interfaces
   */
  private final Set<RequestAfterExecuted> reqsAfterExecuted;

  /**
   * Creates a new DeviceSynchronizer instance.
   *
   * @param reqsBefore implementions of RequestBefore
   * @param querysBefore implementions of QueryBefore
   * @param reqsAtSend implementions of RequestAtSend
   * @param reqsWhile implementions of RequestWhile
   * @param querysAtExecuted implementions of QueryAtExecuted
   * @param reqsAfterExecuted implementions of RequestAfterExecuted
   */
  @Inject
  public DummyDeviceSynchronizer(Set<RequestBefore> reqsBefore,
                                   Set<QueryBefore> querysBefore,
                                   Set<RequestAtSend> reqsAtSend,
                                   Set<RequestWhile> reqsWhile,
                                   Set<QueryAtExecuted> querysAtExecuted,
                                   Set<RequestAfterExecuted> reqsAfterExecuted) {
    this.reqsBefore = requireNonNull(reqsBefore, "reqsBefore");
    this.querysBefore = requireNonNull(querysBefore, "querysBefore");
    this.reqsAtSend = requireNonNull(reqsAtSend, "reqsAtSend");
    this.reqsWhile = requireNonNull(reqsWhile, "reqsWhile");
    this.querysAtExecuted = requireNonNull(querysAtExecuted, "querysAtExecuted");
    this.reqsAfterExecuted = requireNonNull(reqsAfterExecuted, "reqsAfterExecuted");
  }

  @Override
  public ExplainedBoolean requestBefore(@Nonnull String vehicle, @Nonnull MovementCommand command) {
    return new ExplainedBoolean(true, "");
  }

  @Override
  public ExplainedBoolean queryBefore(@Nonnull String vehicle, @Nonnull MovementCommand command) {
    return new ExplainedBoolean(true, "");
  }

  @Override
  public ExplainedBoolean requestAtSend(@Nonnull String vehicle, @Nonnull MovementCommand command) {
    return new ExplainedBoolean(true, "");
  }

  @Override
  public ExplainedBoolean requestWhile(@Nonnull String vehicle, @Nonnull MovementCommand command) {
    return new ExplainedBoolean(true, "");
  }

  @Override
  public ExplainedBoolean queryAtExecuted(@Nonnull String vehicle, @Nonnull MovementCommand command) {
    return new ExplainedBoolean(true, "");
  }
  
  @Override
  public ExplainedBoolean requestAfterExecuted(@Nonnull String vehicle, @Nonnull MovementCommand command) {
    return new ExplainedBoolean(true, "");
  }

}