package org.opentcs.strategies.basic.dispatching;

import com.seer.srd.eventbus.EventBus;
import com.seer.srd.eventbus.VehicleChangedEvent;
import com.seer.srd.route.DispatcherConfiguration;
import com.seer.srd.route.RerouteTrigger;
import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.WhiteBoardKt;
import com.seer.srd.route.service.VehicleService;
import com.seer.srd.vehicle.Vehicle;
import kotlin.jvm.functions.Function1;
import org.opentcs.components.kernel.Dispatcher;
import org.opentcs.data.order.TransportOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Provider;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/**
 * Dispatches transport orders and vehicles.
 */
public class DefaultDispatcher implements Dispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultDispatcher.class);

    private final OrderReservationPool orderReservationPool;

    private final TransportOrderUtil transportOrderUtil;

    private final FullDispatchTask fullDispatchTask;

    private final Provider<PeriodicVehicleRedispatchingTask> periodicDispatchTaskProvider;

    private final RerouteUtil rerouteUtil;
    /**
     *
     */
    private ImplicitDispatchTrigger implicitDispatchTrigger;

    private ScheduledFuture<?> periodicDispatchTaskFuture;

    private boolean initialized;
    private Function1<? super VehicleChangedEvent, ?> onVehicleChanged;

    @Inject
    public DefaultDispatcher(OrderReservationPool orderReservationPool,
                             TransportOrderUtil transportOrderUtil,
                             FullDispatchTask fullDispatchTask,
                             Provider<PeriodicVehicleRedispatchingTask> periodicDispatchTaskProvider,
                             RerouteUtil rerouteUtil) {
        this.orderReservationPool = requireNonNull(orderReservationPool, "orderReservationPool");
        this.transportOrderUtil = requireNonNull(transportOrderUtil, "transportOrderUtil");
        this.fullDispatchTask = requireNonNull(fullDispatchTask, "fullDispatchTask");
        this.periodicDispatchTaskProvider = requireNonNull(periodicDispatchTaskProvider, "periodicDispatchTaskProvider");
        this.rerouteUtil = requireNonNull(rerouteUtil, "rerouteUtil");
    }

    @Override
    public void initialize() {
        if (isInitialized()) {
            return;
        }

        LOG.debug("Initializing...");

        transportOrderUtil.initialize();
        orderReservationPool.clear();

        fullDispatchTask.initialize();

        implicitDispatchTrigger = new ImplicitDispatchTrigger(this);
        this.onVehicleChanged = implicitDispatchTrigger::onVehicleChanged;
        EventBus.INSTANCE.getVehicleEventManager().add(this.onVehicleChanged);

        DispatcherConfiguration cfg = RouteConfigKt.getRouteConfig().getDispatcher();
        LOG.debug("Scheduling periodic dispatch task with interval of {} ms...",
                cfg.getIdleVehicleRedispatchingInterval());
        periodicDispatchTaskFuture = WhiteBoardKt.getKernelExecutor().scheduleAtFixedRate(
                periodicDispatchTaskProvider.get(),
                cfg.getIdleVehicleRedispatchingInterval(),
                cfg.getIdleVehicleRedispatchingInterval(),
                TimeUnit.MILLISECONDS
        );

        initialized = true;
    }

    @Override
    public void terminate() {
        if (!isInitialized()) {
            return;
        }

        LOG.debug("Terminating...");

        periodicDispatchTaskFuture.cancel(false);
        periodicDispatchTaskFuture = null;

        EventBus.INSTANCE.getVehicleEventManager().remove(this.onVehicleChanged);
        implicitDispatchTrigger = null;
        this.onVehicleChanged = null;

        fullDispatchTask.terminate();

        initialized = false;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void dispatch() {
        LOG.debug("Scheduling dispatch task...");
        // Schedule this to be executed by the kernel executor.
        WhiteBoardKt.getKernelExecutor().submit(fullDispatchTask);
    }

    @Override
    public void withdrawOrder(TransportOrder order, boolean immediateAbort) {
        requireNonNull(order, "order");
        checkState(isInitialized(), "Not initialized");

        // Schedule this to be executed by the kernel executor.
        WhiteBoardKt.getKernelExecutor().submit(() -> {
            LOG.debug("Scheduling withdrawal for transport order '{}' (immediate={})...",
                    order.getName(),
                    immediateAbort);
            transportOrderUtil.abortOrder(order, immediateAbort, false);
        });
    }

    @Override
    public void withdrawOrder(Vehicle vehicle, boolean immediateAbort) {
        requireNonNull(vehicle, "vehicle");
        checkState(isInitialized(), "Not initialized");

        // Schedule this to be executed by the kernel executor.
        WhiteBoardKt.getKernelExecutor().submit(() -> {
            LOG.debug("Scheduling withdrawal for vehicle '{}' (immediate={})...",
                    vehicle.getName(),
                    immediateAbort);
            transportOrderUtil.abortOrder(vehicle, immediateAbort, false, false);
        });
    }

    @Override
    public void withdrawOrder(TransportOrder order, boolean immediateAbort, boolean disableVehicle) {
        requireNonNull(order, "order");
        checkState(isInitialized(), "Not initialized");

        // Schedule this to be executed by the kernel executor.
        WhiteBoardKt.getKernelExecutor().submit(() -> {
            LOG.debug("Scheduling withdrawal for transport order '{}' (immediate={}, disable={})...",
                    order.getName(),
                    immediateAbort,
                    disableVehicle);
            transportOrderUtil.abortOrder(order, immediateAbort, disableVehicle);
        });
    }

    @Override
    public void withdrawOrder(Vehicle vehicle, boolean immediateAbort, boolean disableVehicle) {
        requireNonNull(vehicle, "vehicle");
        checkState(isInitialized(), "Not initialized");

        // Schedule this to be executed by the kernel executor.
        WhiteBoardKt.getKernelExecutor().submit(() -> {
            LOG.debug("Scheduling withdrawal for vehicle '{}' (immediate={}, disable={})...",
                    vehicle.getName(),
                    immediateAbort,
                    disableVehicle);
            transportOrderUtil.abortOrder(vehicle, immediateAbort, disableVehicle, false);
        });
    }

    @Override
    public void topologyChanged() {
        if (RouteConfigKt.getRouteConfig().getDispatcher().getRerouteTrigger() == RerouteTrigger.TOPOLOGY_CHANGE) {
            LOG.debug("Scheduling reroute task...");
            WhiteBoardKt.getKernelExecutor().submit(() -> {
                LOG.debug("Rerouting vehicles due to topology change...");
                rerouteUtil.reroute(VehicleService.INSTANCE.listVehicles());
            });
        }
    }

}
