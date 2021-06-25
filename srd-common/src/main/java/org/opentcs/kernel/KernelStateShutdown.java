package org.opentcs.kernel;

import org.opentcs.access.Kernel;

/**
 * This class implements the standard openTCS kernel when it's shut down.
 */
final class KernelStateShutdown extends KernelState {

    private boolean initialized;

    @Override
    public void initialize() {
        initialized = true;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void terminate() {
        initialized = false;
    }

    @Override
    public Kernel.State getState() {
        return Kernel.State.SHUTDOWN;
    }
}
