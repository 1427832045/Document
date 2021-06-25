package org.opentcs.access;

import java.io.Serializable;

public class KernelException extends Exception implements Serializable {

  public KernelException() {
    super();
  }

  public KernelException(String message) {
    super(message);
  }

  public KernelException(String message, Throwable cause) {
    super(message, cause);
  }

  public KernelException(Throwable cause) {
    super(cause);
  }
}
