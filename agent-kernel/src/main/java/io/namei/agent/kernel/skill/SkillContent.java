package io.namei.agent.kernel.skill;

/** Full text is only available to an already selected always Skill and is never a catalog field. */
public record SkillContent(String name, String body) {
  public static final int MAX_BODY_CODE_POINTS = 65_536;

  public SkillContent {
    SkillDescriptor.validateName(name);
    if (body == null || body.strip().isEmpty()) {
      throw SkillContract.violation();
    }
    body = body.strip();
    if (body.codePointCount(0, body.length()) > MAX_BODY_CODE_POINTS) {
      throw SkillContract.violation();
    }
  }
}
