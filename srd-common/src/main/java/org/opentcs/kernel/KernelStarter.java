package org.opentcs.kernel;

import com.seer.srd.route.service.PlantModelService;
import org.opentcs.access.Kernel;
import org.opentcs.access.LocalKernel;
import org.opentcs.components.kernel.KernelExtension;
import org.opentcs.customizations.kernel.ActiveInAllModes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Initializes an openTCS kernel instance.
 */
public class KernelStarter {

    private static final Logger LOG = LoggerFactory.getLogger(KernelStarter.class);

    private final LocalKernel kernel;

    private final Set<KernelExtension> extensions;

    @Inject
    protected KernelStarter(LocalKernel kernel,
                            @ActiveInAllModes Set<KernelExtension> extensions) {
        this.kernel = requireNonNull(kernel, "kernel");
        this.extensions = requireNonNull(extensions, "extensions");
    }

    /**
     * Initializes the system and starts the openTCS kernel including modules.
     */
    public void startKernel() {
        // Register kernel extensions.
        for (KernelExtension extension : extensions) {
            kernel.addKernelExtension(extension);
        }
        // Start local kernel.
        kernel.initialize();
        LOG.debug("Kernel initialized.");

        PlantModelService.INSTANCE.loadPlantModel();
        LOG.info("Loaded model named '{}'.", PlantModelService.INSTANCE.getPlantModel().getName());
        kernel.setState(Kernel.State.OPERATING);

    }
}
