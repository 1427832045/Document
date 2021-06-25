package org.opentcs.kernel;

import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.VehicleSimulation;
import com.seer.srd.route.service.OrderSequenceService;
import com.seer.srd.route.service.TransportOrderService;
import com.seer.srd.route.service.VehicleService;
import com.seer.srd.vehicle.Vehicle;
import com.seer.srd.vehicle.driver.VehicleDriverManager;
import com.seer.srd.vehicle.driver.io.tcp.VehicleTcpSimulationManager;
import org.opentcs.access.Kernel;
import org.opentcs.components.kernel.Dispatcher;
import org.opentcs.components.kernel.KernelExtension;
import org.opentcs.components.kernel.Router;
import org.opentcs.components.kernel.Scheduler;
import org.opentcs.customizations.kernel.ActiveInOperatingMode;
import org.opentcs.data.ObjectUnknownException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.seer.srd.vehicle.VehicleRecoveryManagerKt.rematchOrderSequences;
import static com.seer.srd.vehicle.VehicleRecoveryManagerKt.rematchTransportOrders;
import static com.seer.srd.vehicle.driver.io.http.VehicleHttpSimulationKt.disposeVehicleHttpSimulations;
import static com.seer.srd.vehicle.driver.io.http.VehicleHttpSimulationKt.initVehicleHttpSimulations;
import static java.util.Objects.requireNonNull;

class KernelStateOperating extends KernelStateOnline {

    private static final Logger LOG = LoggerFactory.getLogger(KernelStateOperating.class);

    private final Router router;

    private final Scheduler scheduler;

    private final Dispatcher dispatcher;

    // This kernel state's local extensions.
    private final Set<KernelExtension> extensions;

    private boolean initialized;

    @Inject
    KernelStateOperating(Router router,
                         Scheduler scheduler,
                         Dispatcher dispatcher,
                         @ActiveInOperatingMode Set<KernelExtension> extensions) {
        super(RouteConfigKt.getRouteConfig().getKernelApp().getSaveModelOnTerminateOperating());
        this.router = requireNonNull(router, "router");
        this.scheduler = requireNonNull(scheduler, "scheduler");
        this.dispatcher = requireNonNull(dispatcher, "dispatcher");
        this.extensions = requireNonNull(extensions, "extensions");
    }

    // Implementation of interface Kernel starts here.
    @Override
    public void initialize() {
        if (initialized) return;
        LOG.debug("Initializing operating state...");

        // if (databaseService.getNormalExitFlag()) { // only reset when no need to recover
        //// Reset vehicle states to ensure vehicles are not dispatchable initially.
        //for (Vehicle curVehicle : getTCSObjects(Vehicle.class)) {
        //    setVehicleProcState(curVehicle.getName(), Vehicle.ProcState.UNAVAILABLE);
        //    vehicleService.updateVehicleIntegrationLevel(curVehicle.getName(),
        //            Vehicle.IntegrationLevel.TO_BE_RESPECTED);
        //    setVehicleState(curVehicle.getName(), Vehicle.State.UNKNOWN);
        //    setVehicleTransportOrder(curVehicle.getName(), null);
        //    setVehicleOrderSequence(curVehicle.getName(), null);
        //}
        //}
        LOG.debug("rematch transportOrders and orderSequences...");
        rematchTransportOrders();
        rematchOrderSequences();

        LOG.debug("Initializing scheduler '{}'...", scheduler);
        scheduler.initialize();
        LOG.debug("Initializing router '{}'...", router);
        router.initialize();
        LOG.debug("Initializing dispatcher '{}'...", dispatcher);
        dispatcher.initialize();

        LOG.info("Initializing vehicle driver manager");
        VehicleDriverManager.INSTANCE.init();

        VehicleSimulation vehicleSim = RouteConfigKt.getRouteConfig().getVehicleSimulation();
        if (vehicleSim == VehicleSimulation.Http) {
            initVehicleHttpSimulations();
        } else if (vehicleSim == VehicleSimulation.Tcp) {
            VehicleTcpSimulationManager.INSTANCE.init();
        }

        // Start kernel extensions.
        for (KernelExtension extension : extensions) {
            LOG.debug("Initializing kernel extension '{}'...", extension);
            extension.initialize();
        }
        LOG.debug("Finished initializing kernel extensions.");

        initialized = true;

        LOG.debug("Operating state initialized.");
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void terminate() {
        if (!initialized) {
            LOG.debug("Not initialized.");
            return;
        }
        LOG.debug("Terminating operating state...");
        super.terminate();

        // Terminate everything that may still use resources.
        for (KernelExtension extension : extensions) {
            LOG.debug("Terminating kernel extension '{}'...", extension);
            extension.terminate();
        }
        LOG.debug("Terminated kernel extensions.");

        // Terminate strategies.
        LOG.debug("Terminating dispatcher '{}'...", dispatcher);
        dispatcher.terminate();
        LOG.debug("Terminating router '{}'...", router);
        router.terminate();
        LOG.debug("Terminating scheduler '{}'...", scheduler);
        scheduler.terminate();

        LOG.info("Disposing vehicle driver manager");
        VehicleDriverManager.INSTANCE.dispose();

        VehicleSimulation vehicleSim = RouteConfigKt.getRouteConfig().getVehicleSimulation();
        if (vehicleSim == VehicleSimulation.Http) {
            disposeVehicleHttpSimulations();
        } else if (vehicleSim == VehicleSimulation.Tcp) {
            VehicleTcpSimulationManager.INSTANCE.dispose();
        }

        // Grant communication adapters etc. some time to settle things.
        try {
            TimeUnit.MILLISECONDS.sleep(500);
        } catch (InterruptedException e) {
            //
        }

        // Ensure that vehicles do not reference orders any more.
        for (Vehicle curVehicle : com.seer.srd.route.service.VehicleService.INSTANCE.listVehicles()) {
            setVehicleProcState(curVehicle.getName(), Vehicle.ProcState.UNAVAILABLE);
            com.seer.srd.route.service.VehicleService.INSTANCE.updateVehicleIntegrationLevel(curVehicle.getName(),
                    Vehicle.IntegrationLevel.TO_BE_RESPECTED);
            setVehicleState(curVehicle.getName(), Vehicle.State.UNKNOWN);
            setVehicleTransportOrder(curVehicle.getName(), null);
            setVehicleOrderSequence(curVehicle.getName(), null);
        }

        // Remove all orders and order sequences from the pool.
        TransportOrderService.INSTANCE.clear();
        OrderSequenceService.INSTANCE.clear();
        VehicleService.INSTANCE.clear();

        initialized = false;

        LOG.debug("Operating state terminated.");
    }

    @Override
    public Kernel.State getState() {
        return Kernel.State.OPERATING;
    }

    @Override
    @Deprecated
    public void setVehicleState(String vehicleName, Vehicle.State newState) throws ObjectUnknownException {
        synchronized (getGlobalSyncObject()) {
            com.seer.srd.route.service.VehicleService.INSTANCE.setVehicleState(vehicleName, newState);
        }
    }

    @Override
    @Deprecated
    public void setVehicleProcState(String vehicleName, Vehicle.ProcState newState)
            throws ObjectUnknownException {
        synchronized (getGlobalSyncObject()) {
            LOG.debug("Updating procState of vehicle {} to {}...", vehicleName, newState);
            com.seer.srd.route.service.VehicleService.INSTANCE.setVehicleProcState(vehicleName, newState);
        }
    }

    @Override
    @Deprecated
    public void setVehicleTransportOrder(String vehicleName, String orderName) throws ObjectUnknownException {
        synchronized (getGlobalSyncObject()) {
            com.seer.srd.route.service.VehicleService.INSTANCE.setVehicleTransportOrder(vehicleName, orderName);
        }
    }

    @Override
    @Deprecated
    public void setVehicleOrderSequence(String vehicleName, String seqName)
            throws ObjectUnknownException {
        synchronized (getGlobalSyncObject()) {
            com.seer.srd.route.service.VehicleService.INSTANCE.setVehicleOrderSequence(vehicleName, seqName);
        }
    }
}
