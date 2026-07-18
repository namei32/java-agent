package io.namei.agent.kernel.proactive;

import java.util.Objects;

public final class ProactiveContractViolation extends IllegalArgumentException {
  private final ProactiveStableCode code;

  public ProactiveContractViolation(ProactiveStableCode code) {
    super("Proactive Contract 被拒绝: " + Objects.requireNonNull(code, "code").name());
    this.code = code;
  }

  public ProactiveStableCode code() {
    return code;
  }
}
