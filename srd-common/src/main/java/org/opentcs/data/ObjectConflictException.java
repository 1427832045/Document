package org.opentcs.data;

import org.opentcs.access.KernelRuntimeException;

public class ObjectConflictException extends KernelRuntimeException {

    public ObjectConflictException(String message) {
        super(message);
    }

    //public ObjectUnknownException(TCSObjectReference<?> ref) {
    //    super("Object unknown: " + (ref == null ? "<null>" : ref.toString()));
    //}

    public ObjectConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
