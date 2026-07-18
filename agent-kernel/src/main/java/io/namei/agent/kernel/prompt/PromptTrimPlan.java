package io.namei.agent.kernel.prompt;

import java.util.List;
import java.util.Set;

public enum PromptTrimPlan {
  FULL(Set.of()),
  TRIM_SKILLS_CATALOG(Set.of(PromptSectionId.SKILLS_CATALOG)),
  TRIM_ACTIVE_SKILLS(Set.of(PromptSectionId.SKILLS_CATALOG, PromptSectionId.ACTIVE_SKILLS)),
  TRIM_LONG_TERM_MEMORY(
      Set.of(
          PromptSectionId.SKILLS_CATALOG,
          PromptSectionId.ACTIVE_SKILLS,
          PromptSectionId.LONG_TERM_MEMORY)),
  TRIM_RETRIEVED_MEMORY(
      Set.of(
          PromptSectionId.SKILLS_CATALOG,
          PromptSectionId.ACTIVE_SKILLS,
          PromptSectionId.LONG_TERM_MEMORY,
          PromptSectionId.RETRIEVED_MEMORY));

  private final Set<PromptSectionId> removed;

  PromptTrimPlan(Set<PromptSectionId> removed) {
    this.removed = Set.copyOf(removed);
  }

  public boolean removes(PromptSectionId id) {
    return removed.contains(id);
  }

  public List<PromptSectionId> removedInSectionOrder() {
    return removed.stream()
        .sorted((left, right) -> Integer.compare(left.priority(), right.priority()))
        .toList();
  }
}
