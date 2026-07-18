package io.namei.agent.kernel.skill;

import java.util.Optional;

@FunctionalInterface
public interface SkillCatalogPort {
  SkillCatalogSnapshot snapshot();

  /** Returns an already audited, available Skill body without exposing a filesystem location. */
  default Optional<SkillContent> readAvailable(String name) {
    return Optional.empty();
  }

  static SkillCatalogPort disabled() {
    return SkillCatalogSnapshot::empty;
  }
}
