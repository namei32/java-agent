package io.namei.agent.kernel.skill;

/** 完整正文仅向已选中的 Always Skill 提供，绝不是 Catalog 字段。 */
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
