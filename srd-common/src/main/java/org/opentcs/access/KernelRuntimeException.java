package org.opentcs.access;

import java.io.Serializable;

public class KernelRuntimeException extends RuntimeException implements Serializable {

    public KernelRuntimeException() {
        super();
    }

    public KernelRuntimeException(String message) {
        super(message);
    }

    public KernelRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }

    public KernelRuntimeException(Throwable cause) {
        super(cause);
    }
}
