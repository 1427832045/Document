package org.opentcs.kernel;

/**
 * The base class for the kernel's online states.
 */
abstract class KernelStateOnline extends KernelState {

    private final boolean saveModelOnTerminate;

    public KernelStateOnline(boolean saveModelOnTerminate) {
        this.saveModelOnTerminate = saveModelOnTerminate;
    }

    @Override
    public void terminate() {
        if (saveModelOnTerminate) {
            //savePlantModel();
        }
    }

}
