package org.opentcs.kernel.services;

import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.WhiteBoardKt;
import com.seer.srd.route.service.PlantModelService;
import org.opentcs.access.Kernel;
import org.opentcs.access.LocalKernel;
import org.opentcs.components.kernel.Dispatcher;
import org.opentcs.components.kernel.Router;
import org.opentcs.components.kernel.services.RouterService;
import org.opentcs.data.ObjectUnknownException;

import javax.inject.Inject;

import static java.util.Objects.requireNonNull;

/**
 * This class is the standard implementation of the {@link RouterService} interface.
 */
public class StandardRouterService implements RouterService {

    private final LocalKernel kernel;

    private final Router router;

    private final Dispatcher dispatcher;

    @Inject
    public StandardRouterService(LocalKernel kernel, Router router, Dispatcher dispatcher) {
        this.kernel = requireNonNull(kernel, "kernel");
        this.router = requireNonNull(router, "router");
        this.dispatcher = requireNonNull(dispatcher, "dispatcher");
    }

    @Override
    public void updatePathLock(String pathName, boolean locked) throws ObjectUnknownException {
        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            PlantModelService.INSTANCE.setPathLocked(pathName, locked);
            if (kernel.getState() == Kernel.State.OPERATING
                    && RouteConfigKt.getRouteConfig().getKernelApp().getUpdateRoutingTopologyOnPathLockChange()) {
                updateRoutingTopology();
            }
        }
    }

    @Override
    public void updateRoutingTopology() {
        synchronized (WhiteBoardKt.getGlobalSyncObject()) {
            router.topologyChanged();
            dispatcher.topologyChanged();
        }
    }
}
