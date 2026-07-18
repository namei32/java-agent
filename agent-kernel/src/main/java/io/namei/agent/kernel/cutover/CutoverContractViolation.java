package io.namei.agent.kernel.cutover;

import java.util.Objects;

public final class CutoverContractViolation extends IllegalArgumentException {
  private final CutoverStableCode code;

  public CutoverContractViolation(CutoverStableCode code) {
    super("Cutover Contract 被拒绝: " + Objects.requireNonNull(code, "code").name());
    this.code = code;
  }

  public CutoverStableCode code() {
    return code;
  }
}
