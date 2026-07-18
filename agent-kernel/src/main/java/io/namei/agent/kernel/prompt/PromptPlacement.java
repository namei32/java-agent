package io.namei.agent.kernel.prompt;

public enum PromptPlacement {
  SYSTEM,
  CONTEXT_FRAME;

  public static PromptPlacement parse(String value) {
    try {
      PromptPlacement parsed = valueOf(value);
      if (!parsed.name().equals(value)) {
        throw new IllegalArgumentException();
      }
      return parsed;
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw PromptContract.violation(PromptStableCode.PROMPT_CONTRACT_INVALID);
    }
  }
}
