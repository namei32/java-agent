package io.namei.agent.application;

import java.util.Objects;

/** Safe Prompt contributions rendered from one immutable Skill catalog snapshot. */
public record SkillPromptSections(String catalog, String active) {
  public SkillPromptSections {
    catalog = Objects.requireNonNull(catalog, "catalog").strip();
    active = Objects.requireNonNull(active, "active");
  }

  public static SkillPromptSections empty() {
    return new SkillPromptSections("", "");
  }
}
