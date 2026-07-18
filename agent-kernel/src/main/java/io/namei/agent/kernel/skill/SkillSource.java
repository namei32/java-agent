package io.namei.agent.kernel.skill;

public enum SkillSource {
  BUILTIN,
  WORKSPACE;

  public static SkillSource parse(String value) {
    try {
      SkillSource parsed = valueOf(value);
      if (!parsed.name().equals(value)) {
        throw new IllegalArgumentException();
      }
      return parsed;
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw SkillContract.violation();
    }
  }
}
