package org.opentcs.customizations;

import com.google.inject.AbstractModule;
import org.opentcs.configuration.ConfigurationBindingProvider;

/**
 * A base class for Guice modules adding or customizing bindings for the kernel application and the
 * plant overview application.
 */
public abstract class ConfigurableInjectionModule extends AbstractModule {

    private ConfigurationBindingProvider configBindingProvider;

    public ConfigurationBindingProvider getConfigBindingProvider() {
        return configBindingProvider;
    }

    public void setConfigBindingProvider(ConfigurationBindingProvider configBindingProvider) {
        this.configBindingProvider = configBindingProvider;
    }
}
