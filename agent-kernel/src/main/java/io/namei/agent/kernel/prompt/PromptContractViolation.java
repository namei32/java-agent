package io.namei.agent.kernel.prompt;

import java.util.Objects;

public final class PromptContractViolation extends IllegalArgumentException {
  private final PromptStableCode code;

  public PromptContractViolation(PromptStableCode code) {
    super("Prompt Contract 被拒绝: " + Objects.requireNonNull(code, "code").name());
    this.code = code;
  }

  public PromptStableCode code() {
    return code;
  }
}
