package io.namei.agent.kernel.skill;

public enum SkillCatalogMode {
  DISABLED,
  READ_ONLY;

  public static SkillCatalogMode parse(String value) {
    try {
      SkillCatalogMode parsed = valueOf(value);
      if (!parsed.name().equals(value)) {
        throw new IllegalArgumentException();
      }
      return parsed;
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw SkillContract.violation();
    }
  }
}
