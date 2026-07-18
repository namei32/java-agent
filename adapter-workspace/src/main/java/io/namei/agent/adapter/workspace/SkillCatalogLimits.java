package io.namei.agent.adapter.workspace;

/** Immutable I/O budgets for one read-only Skill catalog snapshot. */
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
