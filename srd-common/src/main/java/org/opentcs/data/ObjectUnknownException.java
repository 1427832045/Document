package org.opentcs.data;

import org.opentcs.access.KernelRuntimeException;

public class ObjectUnknownException extends KernelRuntimeException {

    public ObjectUnknownException(String message) {
        super(message);
    }

    //public ObjectUnknownException(TCSObjectReference<?> ref) {
    //    super("Object unknown: " + (ref == null ? "<null>" : ref.toString()));
    //}

    public ObjectUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
