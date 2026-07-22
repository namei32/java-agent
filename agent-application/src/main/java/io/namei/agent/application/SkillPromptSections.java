package io.namei.agent.application;

import java.util.Objects;

/** 从单个不可变 Skill Catalog 快照渲染出的安全 Prompt 内容。 */
public record SkillPromptSections(String catalog, String active) {
  public SkillPromptSections {
    catalog = Objects.requireNonNull(catalog, "catalog").strip();
    active = Objects.requireNonNull(active, "active");
  }

  public static SkillPromptSections empty() {
    return new SkillPromptSections("", "");
  }
}
