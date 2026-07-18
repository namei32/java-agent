package io.namei.agent.kernel.prompt;

public enum PromptMode {
  MINIMAL,
  AKASHIC_CORE;

  public static PromptMode parse(String value) {
    try {
      PromptMode parsed = valueOf(value);
      if (!parsed.name().equals(value)) {
        throw new IllegalArgumentException();
      }
      return parsed;
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw PromptContract.violation(PromptStableCode.PROMPT_MODE_INVALID);
    }
  }
}
