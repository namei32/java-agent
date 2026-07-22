package io.namei.agent.kernel.skill;

import java.util.Optional;

@FunctionalInterface
public interface SkillCatalogPort {
  SkillCatalogSnapshot snapshot();

  /** 返回已经审计且可用的 Skill 正文，不暴露文件系统位置。 */
  default Optional<SkillContent> readAvailable(String name) {
    return Optional.empty();
  }

  static SkillCatalogPort disabled() {
    return SkillCatalogSnapshot::empty;
  }
}
