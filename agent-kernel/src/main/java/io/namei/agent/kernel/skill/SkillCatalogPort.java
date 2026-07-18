package io.namei.agent.kernel.skill;

@FunctionalInterface
public interface SkillCatalogPort {
  SkillCatalogSnapshot snapshot();

  static SkillCatalogPort disabled() {
    return SkillCatalogSnapshot::empty;
  }
}
