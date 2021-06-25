package org.opentcs.drivers.vehicle;

import java.io.Serializable;

import static java.util.Objects.requireNonNull;

/**
 * An event emitted by a communication adapter.
 */
public class VehicleCommAdapterEvent implements Serializable {

    private final String adapterName;

    /**
     * An optional appendix containing additional arbitrary information about the event.
     */
    private final Serializable appendix;

    public VehicleCommAdapterEvent(String adapterName, Serializable appendix) {
        this.adapterName = requireNonNull(adapterName, "adapterName");
        this.appendix = appendix;
    }

    public String getAdapterName() {
        return adapterName;
    }

    public Serializable getAppendix() {
        return appendix;
    }

    @Override
    public String toString() {
        return "VehicleCommAdapterEvent{"
                + "adapterName=" + adapterName
                + ", appendix=" + appendix
                + '}';
    }

}
