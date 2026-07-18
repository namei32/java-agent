package io.namei.agent.kernel.skill;

final class SkillContract {
  static final int CURRENT_VERSION = 1;

  private SkillContract() {}

  static SkillContractViolation violation() {
    return new SkillContractViolation(SkillStableCode.SKILL_CONTRACT_INVALID);
  }
}
