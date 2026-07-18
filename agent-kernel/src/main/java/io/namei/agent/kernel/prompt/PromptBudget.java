package io.namei.agent.kernel.prompt;

public record PromptBudget(
    int maxSystemTokens, int maxFrameTokens, int maxTotalTokens, int maxSections) {
  private static final int MAX_SECTION_TOKENS = 100_000;
  private static final int MAX_TOTAL_TOKENS = 200_000;
  private static final int MAX_SECTIONS = PromptSectionId.values().length;

  public PromptBudget {
    if (maxSystemTokens < 1
        || maxSystemTokens > MAX_SECTION_TOKENS
        || maxFrameTokens < 1
        || maxFrameTokens > MAX_SECTION_TOKENS
        || maxTotalTokens < maxSystemTokens + maxFrameTokens
        || maxTotalTokens > MAX_TOTAL_TOKENS
        || maxSections < 1
        || maxSections > MAX_SECTIONS) {
      throw PromptContract.violation(PromptStableCode.PROMPT_CONTRACT_INVALID);
    }
  }
}
