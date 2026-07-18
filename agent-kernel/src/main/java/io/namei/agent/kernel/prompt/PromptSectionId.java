package io.namei.agent.kernel.prompt;

import java.util.Arrays;

public enum PromptSectionId {
  IDENTITY("identity", 10, PromptPlacement.SYSTEM, false),
  BEHAVIOR_RULES("behavior_rules", 15, PromptPlacement.SYSTEM, false),
  SKILLS_CATALOG("skills_catalog", 20, PromptPlacement.SYSTEM, true),
  SELF_MODEL("self_model", 30, PromptPlacement.SYSTEM, false),
  LONG_TERM_MEMORY("long_term_memory", 35, PromptPlacement.SYSTEM, true),
  SESSION_CONTEXT("session_context", 40, PromptPlacement.SYSTEM, false),
  RECENT_CONTEXT("recent_context", 45, PromptPlacement.CONTEXT_FRAME, false),
  ACTIVE_SKILLS("active_skills", 50, PromptPlacement.CONTEXT_FRAME, true),
  RETRIEVED_MEMORY("retrieved_memory", 55, PromptPlacement.CONTEXT_FRAME, true);

  private final String value;
  private final int priority;
  private final PromptPlacement placement;
  private final boolean trimAllowed;

  PromptSectionId(String value, int priority, PromptPlacement placement, boolean trimAllowed) {
    this.value = value;
    this.priority = priority;
    this.placement = placement;
    this.trimAllowed = trimAllowed;
  }

  public static PromptSectionId parse(String value) {
    if (value == null) {
      throw PromptContract.violation(PromptStableCode.PROMPT_CONTRACT_INVALID);
    }
    return Arrays.stream(values())
        .filter(candidate -> candidate.value.equals(value))
        .findFirst()
        .orElseThrow(() -> PromptContract.violation(PromptStableCode.PROMPT_CONTRACT_INVALID));
  }

  public String value() {
    return value;
  }

  public int priority() {
    return priority;
  }

  public PromptPlacement placement() {
    return placement;
  }

  public boolean trimAllowed() {
    return trimAllowed;
  }
}
