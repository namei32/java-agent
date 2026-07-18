package io.namei.agent.kernel.skill;

import java.util.Objects;

public final class SkillContractViolation extends IllegalArgumentException {
  private final SkillStableCode code;

  public SkillContractViolation(SkillStableCode code) {
    super("Skill Catalog Contract 被拒绝: " + Objects.requireNonNull(code, "code").name());
    this.code = code;
  }

  public SkillStableCode code() {
    return code;
  }
}
