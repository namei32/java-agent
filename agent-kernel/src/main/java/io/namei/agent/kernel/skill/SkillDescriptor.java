package io.namei.agent.kernel.skill;

import java.util.Objects;
import java.util.regex.Pattern;

/** Safe catalog projection. It intentionally excludes physical locations and full Skill text. */
public record SkillDescriptor(
    String name, String description, SkillSource source, boolean available, boolean always) {
  static final int MAX_DESCRIPTION_CODE_POINTS = 512;
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");

  public SkillDescriptor {
    validateName(name);
    if (description == null
        || description.strip().isEmpty()
        || description.indexOf('\n') >= 0
        || description.indexOf('\r') >= 0) {
      throw SkillContract.violation();
    }
    description = description.strip();
    if (description.codePointCount(0, description.length()) > MAX_DESCRIPTION_CODE_POINTS) {
      throw SkillContract.violation();
    }
    source = Objects.requireNonNull(source, "source");
  }

  static void validateName(String value) {
    if (value == null || !NAME.matcher(value).matches()) {
      throw SkillContract.violation();
    }
  }
}
