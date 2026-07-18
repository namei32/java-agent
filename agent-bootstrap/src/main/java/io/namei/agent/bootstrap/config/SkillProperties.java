package io.namei.agent.bootstrap.config;

import io.namei.agent.adapter.workspace.SkillCatalogLimits;
import io.namei.agent.kernel.skill.SkillCatalogMode;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Strict, default-off configuration for the R12-S1 read-only Skill catalog. */
@ConfigurationProperties("agent.skills")
public final class SkillProperties {
  private static final int MAX_PROMPT_CODE_POINTS = 1_000_000;

  private final SkillCatalogMode mode;
  private final Optional<Path> builtinRoot;
  private final int maxSkills;
  private final int maxFileBytes;
  private final int maxCatalogCodePoints;
  private final int maxActiveCodePoints;
  private final int maxReadCodePoints;

  public SkillProperties(
      String mode,
      String builtinRoot,
      int maxSkills,
      int maxFileBytes,
      int maxCatalogCodePoints,
      int maxActiveCodePoints) {
    this(
        mode,
        builtinRoot,
        maxSkills,
        maxFileBytes,
        maxCatalogCodePoints,
        maxActiveCodePoints,
        20_000);
  }

  @ConstructorBinding
  public SkillProperties(
      @DefaultValue("DISABLED") String mode,
      @DefaultValue("") String builtinRoot,
      @DefaultValue("64") int maxSkills,
      @DefaultValue("65536") int maxFileBytes,
      @DefaultValue("32768") int maxCatalogCodePoints,
      @DefaultValue("32768") int maxActiveCodePoints,
      @DefaultValue("20000") int maxReadCodePoints) {
    this.mode = SkillCatalogMode.parse(mode);
    this.builtinRoot = parseOptionalRoot(builtinRoot);
    new SkillCatalogLimits(maxSkills, maxFileBytes);
    if (maxCatalogCodePoints < 1
        || maxCatalogCodePoints > MAX_PROMPT_CODE_POINTS
        || maxActiveCodePoints < 1
        || maxActiveCodePoints > MAX_PROMPT_CODE_POINTS
        || maxReadCodePoints < 1
        || maxReadCodePoints > 65_536) {
      throw new IllegalArgumentException("Skill Prompt 预算无效");
    }
    this.maxSkills = maxSkills;
    this.maxFileBytes = maxFileBytes;
    this.maxCatalogCodePoints = maxCatalogCodePoints;
    this.maxActiveCodePoints = maxActiveCodePoints;
    this.maxReadCodePoints = maxReadCodePoints;
  }

  public SkillCatalogMode mode() {
    return mode;
  }

  public Optional<Path> builtinRoot() {
    return builtinRoot;
  }

  public int maxSkills() {
    return maxSkills;
  }

  public int maxFileBytes() {
    return maxFileBytes;
  }

  public int maxCatalogCodePoints() {
    return maxCatalogCodePoints;
  }

  public int maxActiveCodePoints() {
    return maxActiveCodePoints;
  }

  public int maxReadCodePoints() {
    return maxReadCodePoints;
  }

  @Override
  public String toString() {
    return "SkillProperties[mode=" + mode + ", roots=<configured>, budgets=<configured>]";
  }

  private static Optional<Path> parseOptionalRoot(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Path.of(value).toAbsolutePath().normalize());
    } catch (InvalidPathException invalid) {
      throw new IllegalArgumentException("agent.skills.builtin-root 无效", invalid);
    }
  }
}
