package org.opentcs.kernel;

import com.seer.srd.route.WhiteBoardKt;
import org.opentcs.access.Kernel.State;
import org.opentcs.components.Lifecycle;
import org.opentcs.data.ObjectUnknownException;
import com.seer.srd.vehicle.Vehicle;

/**
 * The abstract base class for classes that implement state specific kernel behaviour.
 */
abstract class KernelState implements Lifecycle {

    public abstract State getState();

    @Deprecated
    protected void setVehicleState(String vehicleName, Vehicle.State newState) throws ObjectUnknownException {
        // Do nada.
        // This method does not throw an exception because, when switching kernel
        // states, vehicle drivers are shut down and reset their vehicles' states
        // via this method; when done too late, calling this method leads to an
        // undesired exception.
        // XXX Maybe there's a cleaner way to handle this...
    }

    @Deprecated
    protected void setVehicleProcState(String vehicleName, Vehicle.ProcState newState) throws ObjectUnknownException {
        throw new UnsupportedOperationException(unsupportedMsg());
    }

    @Deprecated
    protected void setVehicleTransportOrder(String vehicleName, String orderName) throws ObjectUnknownException {
        throw new UnsupportedOperationException(unsupportedMsg());
    }

    @Deprecated
    protected void setVehicleOrderSequence(String vehicleRef, String seqName) throws ObjectUnknownException {
        throw new UnsupportedOperationException(unsupportedMsg());
    }

    protected Object getGlobalSyncObject() {
        return WhiteBoardKt.getGlobalSyncObject();
    }

    private String unsupportedMsg() {
        return "Called operation not supported in this kernel mode (" + getState().name() + ").";
    }
}
