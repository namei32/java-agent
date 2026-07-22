package io.namei.agent.adapter.workspace;

/** 单个只读 Skill Catalog 快照的不可变 I/O 预算。 */
public record SkillCatalogLimits(int maxSkills, int maxFileBytes) {
  private static final int MAX_SKILLS = 64;
  private static final int MAX_FILE_BYTES = 1_048_576;

  public SkillCatalogLimits {
    if (maxSkills < 1
        || maxSkills > MAX_SKILLS
        || maxFileBytes < 1
        || maxFileBytes > MAX_FILE_BYTES) {
      throw new IllegalArgumentException("Skill Catalog 限额无效");
    }
  }
}
