package io.namei.agent.bootstrap.control;

import io.namei.agent.kernel.control.ControlStableCode;
import java.util.Objects;

public final class ControlRequestRejectedException extends RuntimeException {
  private final ControlStableCode code;
  private final int httpStatus;

  ControlRequestRejectedException(ControlStableCode code, int httpStatus) {
    super("控制面请求被拒绝: " + Objects.requireNonNull(code, "code").name(), null, false, false);
    this.code = code;
    this.httpStatus = httpStatus;
  }

  public ControlStableCode code() {
    return code;
  }

  public int httpStatus() {
    return httpStatus;
  }
}
