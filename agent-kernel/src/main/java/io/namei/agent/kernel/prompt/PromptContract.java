package io.namei.agent.kernel.prompt;

final class PromptContract {
  static final int CURRENT_VERSION = 1;

  private PromptContract() {}

  static PromptContractViolation violation(PromptStableCode code) {
    return new PromptContractViolation(code);
  }
}
