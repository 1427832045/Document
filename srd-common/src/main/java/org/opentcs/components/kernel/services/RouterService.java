package org.opentcs.components.kernel.services;

import org.opentcs.access.KernelRuntimeException;
import org.opentcs.components.kernel.Router;

/**
 * Provides methods concerning the {@link Router}.
 */
public interface RouterService {

    /**
     * Updates a path's lock state.
     *
     * @param pathName A reference to the path to be updated.
     * @param locked   Indicates whether the path is to be locked ({@code true}) or unlocked
     *                 ({@code false}).
     * @throws KernelRuntimeException In case there is an exception executing this method.
     */
    void updatePathLock(String pathName, boolean locked) throws KernelRuntimeException;

    /**
     * Notifies the router that the topology has changed in a significant way and needs to be
     * re-evaluated.
     *
     * @throws KernelRuntimeException In case there is an exception executing this method.
     */
    void updateRoutingTopology() throws KernelRuntimeException;
}
