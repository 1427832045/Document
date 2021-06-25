package org.opentcs.kernel;

import com.seer.srd.route.RouteConfigKt;
import org.opentcs.access.Kernel;
import org.opentcs.components.kernel.KernelExtension;
import org.opentcs.customizations.kernel.ActiveInModellingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * This class implements the standard openTCS kernel in modelling mode.
 */
class KernelStateModelling extends KernelStateOnline {

    private static final Logger LOG = LoggerFactory.getLogger(KernelStateModelling.class);
    /**
     * This kernel state's local extensions.
     */
    private final Set<KernelExtension> extensions;

    private boolean initialized;

    @Inject
    KernelStateModelling(@ActiveInModellingMode Set<KernelExtension> extensions) {
        super(RouteConfigKt.getRouteConfig().getKernelApp().getSaveModelOnTerminateModelling());
        this.extensions = requireNonNull(extensions, "extensions");
    }

    @Override
    public void initialize() {
        if (initialized) throw new IllegalStateException("Already initialized");
        LOG.debug("Initializing modelling state...");

        // Start kernel extensions.
        for (KernelExtension extension : extensions) {
            LOG.debug("Initializing kernel extension '{}'...", extension);
            extension.initialize();
        }
        LOG.debug("Finished initializing kernel extensions.");

        initialized = true;

        LOG.debug("Modelling state initialized.");
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void terminate() {
        if (!initialized) {
            throw new IllegalStateException("Not initialized, cannot terminate");
        }
        LOG.debug("Terminating modelling state...");
        super.terminate();

        // Terminate everything that may still use resources.
        for (KernelExtension extension : extensions) {
            LOG.debug("Terminating kernel extension '{}'...", extension);
            extension.terminate();
        }
        LOG.debug("Terminated kernel extensions.");

        initialized = false;

        LOG.debug("Modelling state terminated.");
    }

    @Override
    public Kernel.State getState() {
        return Kernel.State.MODELLING;
    }

}
