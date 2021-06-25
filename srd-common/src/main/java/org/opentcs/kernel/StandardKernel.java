package org.opentcs.kernel;

import com.google.inject.Provider;
import com.seer.srd.route.WhiteBoardKt;
import org.opentcs.access.Kernel;
import org.opentcs.access.LocalKernel;
import org.opentcs.components.kernel.KernelExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * This class implements the standard openTCS kernel.
 * YY：KernelStateTransitionEvent 没人监听，就不触发了
 */
final class StandardKernel implements LocalKernel, Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(StandardKernel.class);

    // A map to state providers used when switching kernel states.
    private final Map<Kernel.State, Provider<KernelState>> stateProviders;

    // An event hub for synchronous dispatching of events.
    //@SuppressWarnings("deprecation")
    //private final org.opentcs.util.eventsystem.EventHub<org.opentcs.util.eventsystem.TCSEvent> eventHub;

    // This kernel's order receivers.
    private final Set<KernelExtension> kernelExtensions = new HashSet<>();

    // Functions as a barrier for the kernel's {@link #run() run()} method.
    private final Semaphore terminationSemaphore = new Semaphore(0);

    private volatile boolean initialized;

    private KernelState kernelState;

    @Inject
    StandardKernel(Map<Kernel.State, Provider<KernelState>> stateProviders) {
        this.stateProviders = requireNonNull(stateProviders, "stateProviders");
    }

    @Override
    public void initialize() {
        if (isInitialized()) return;
        // First of all, start all kernel extensions that are already registered.
        for (KernelExtension extension : kernelExtensions) {
            LOG.debug("Initializing extension: {}", extension.getClass().getName());
            extension.initialize();
        }

        // Initial state is modelling.
        setState(State.MODELLING);

        initialized = true;
        LOG.debug("Starting kernel thread");
        Thread kernelThread = new Thread(this, "kernelThread");
        kernelThread.start();
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void terminate() {
        if (!isInitialized()) return;
        // Note that the actual shutdown of extensions should happen when the kernel
        // thread (see run()) finishes, not here.
        // Set the terminated flag and wake up this kernel's thread for termination.
        initialized = false;
        terminationSemaphore.release();
    }

    @Override
    public void run() {
        // Wait until terminated.
        terminationSemaphore.acquireUninterruptibly();
        LOG.info("Terminating...");
        // Sleep a bit so clients have some time to receive an event for the
        // SHUTDOWN state change and shut down gracefully themselves.
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            //
        }
        // Shut down all kernel extensions.
        LOG.debug("Shutting down kernel extensions...");
        for (KernelExtension extension : kernelExtensions) {
            extension.terminate();
        }
        WhiteBoardKt.getKernelExecutor().shutdown();
        // databaseService.setNormalExitFlag(true);
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            //
        }
        LOG.info("Kernel thread finished.");
    }

    @Override
    public State getState() {
        return kernelState.getState();
    }

    @Override
    public void setState(State newState) throws IllegalArgumentException {
        Objects.requireNonNull(newState, "newState is null");
        final Kernel.State oldState;
        if (kernelState != null) {
            oldState = kernelState.getState();
            // Don't do anything if the new state is the same as the current one.
            if (oldState == newState) {
                LOG.debug("Already in state '{}', doing nothing.", newState.name());
                return;
            }
            // Terminate previous state.
            kernelState.terminate();
        } else {
            oldState = null;
        }
        LOG.info("Switching kernel to state '{}'", newState.name());
        switch (newState) {
            case SHUTDOWN:
                kernelState = stateProviders.get(Kernel.State.SHUTDOWN).get();
                kernelState.initialize();
                terminate();
                break;
            case MODELLING:
                kernelState = stateProviders.get(Kernel.State.MODELLING).get();
                kernelState.initialize();
                break;
            case OPERATING:
                kernelState = stateProviders.get(Kernel.State.OPERATING).get();
                kernelState.initialize();
                break;
            default:
                throw new IllegalArgumentException("Unexpected state: " + newState);
        }

        // todo 原来这里发布 UserNotification 告知内核状态
    }

    @Override
    public void addKernelExtension(final KernelExtension newExtension) {

        Objects.requireNonNull(newExtension, "newExtension is null");
        kernelExtensions.add(newExtension);
    }

    // Event management methods start here.
    //@Override
    //@Deprecated
    //public void addEventListener(
    //        org.opentcs.util.eventsystem.EventListener<org.opentcs.util.eventsystem.TCSEvent> listener,
    //        org.opentcs.util.eventsystem.EventFilter<org.opentcs.util.eventsystem.TCSEvent> filter) {
    //
    //    eventHub.addEventListener(listener, filter);
    //}

    //@Override
    //@Deprecated
    //public void addEventListener(
    //        org.opentcs.util.eventsystem.EventListener<org.opentcs.util.eventsystem.TCSEvent> listener) {
    //    eventHub.addEventListener(listener);
    //}
    //
    //@Override
    //@Deprecated
    //public void removeEventListener(
    //        org.opentcs.util.eventsystem.EventListener<org.opentcs.util.eventsystem.TCSEvent> listener) {
    //
    //    eventHub.removeEventListener(listener);
    //}

    // Methods not declared in any interface start here.

}
