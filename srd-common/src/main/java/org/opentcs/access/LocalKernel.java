package org.opentcs.access;

import org.opentcs.components.Lifecycle;
import org.opentcs.components.kernel.KernelExtension;

/**
 * Declares the methods the openTCS kernel must provide which are not accessible to remote peers.
 */
public interface LocalKernel extends Kernel, Lifecycle {

    /**
     * Adds a <code>KernelExtension</code> to this kernel.
     */
    void addKernelExtension(KernelExtension newExtension);

}
