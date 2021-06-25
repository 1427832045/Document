package org.opentcs.customizations.kernel;

import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import org.opentcs.components.kernel.Dispatcher;
import org.opentcs.components.kernel.KernelExtension;
import org.opentcs.components.kernel.Router;
import org.opentcs.components.kernel.Scheduler;
import org.opentcs.customizations.ConfigurableInjectionModule;

/**
 * A base class for Guice modules adding or customizing bindings for the kernel application.
 */
public abstract class KernelInjectionModule extends ConfigurableInjectionModule {

    /**
     * Sets the scheduler implementation to be used.
     *
     * @param clazz The implementation.
     */
    protected void bindScheduler(Class<? extends Scheduler> clazz) {
        bind(Scheduler.class).to(clazz).in(Singleton.class);
    }

    /**
     * Sets the router implementation to be used.
     *
     * @param clazz The implementation.
     */
    protected void bindRouter(Class<? extends Router> clazz) {
        bind(Router.class).to(clazz).in(Singleton.class);
    }

    /**
     * Sets the parking position supplier implementation to be used.
     *
     * @param clazz The implementation.
     * @deprecated Will be removed along with the deprecated supplier interface.
     */
    @Deprecated
    protected void bindParkingPositionSupplier(
            Class<? extends org.opentcs.components.kernel.ParkingPositionSupplier> clazz) {
        bind(org.opentcs.components.kernel.ParkingPositionSupplier.class).to(clazz).in(Singleton.class);
    }

    /**
     * Sets the recharge position supplier implementation to be used.
     *
     * @param clazz The implementation.
     * @deprecated Will be removed along with the deprecated supplier interface.
     */
    @Deprecated
    protected void bindRechargePositionSupplier(
            Class<? extends org.opentcs.components.kernel.RechargePositionSupplier> clazz) {
        bind(org.opentcs.components.kernel.RechargePositionSupplier.class).to(clazz).in(Singleton.class);
    }

    /**
     * Sets the dispatcher implementation to be used.
     *
     * @param clazz The implementation.
     */
    protected void bindDispatcher(Class<? extends Dispatcher> clazz) {
        bind(Dispatcher.class).to(clazz).in(Singleton.class);
    }

    /**
     * Returns a multibinder that can be used to register kernel extensions for all kernel states.
     *
     * @return The multibinder.
     */
    protected Multibinder<KernelExtension> extensionsBinderAllModes() {
        return Multibinder.newSetBinder(binder(), KernelExtension.class, ActiveInAllModes.class);
    }

    /**
     * Returns a multibinder that can be used to register kernel extensions for the kernel's modelling
     * state.
     *
     * @return The multibinder.
     */
    protected Multibinder<KernelExtension> extensionsBinderModelling() {
        return Multibinder.newSetBinder(binder(), KernelExtension.class, ActiveInModellingMode.class);
    }

    /**
     * Returns a multibinder that can be used to register kernel extensions for the kernel's operating
     * state.
     *
     * @return The multibinder.
     */
    protected Multibinder<KernelExtension> extensionsBinderOperating() {
        return Multibinder.newSetBinder(binder(), KernelExtension.class, ActiveInOperatingMode.class);
    }
}
