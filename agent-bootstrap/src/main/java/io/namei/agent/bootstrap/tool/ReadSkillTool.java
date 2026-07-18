package io.namei.agent.bootstrap.tool;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.skill.SkillCatalogPort;
import io.namei.agent.kernel.skill.SkillContent;
import io.namei.agent.kernel.skill.SkillDescriptor;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Reads only a named, already audited and available R12 Skill body. */
public final class ReadSkillTool implements Tool {
  private static final ToolDefinition DEFINITION =
      new ToolDefinition(
          "read_skill",
          "读取已审计且可用的 Skill 正文，不暴露文件路径。",
          Map.of(
              "type",
              "object",
              "properties",
              Map.of("name", Map.of("type", "string")),
              "required",
              java.util.List.of("name"),
              "additionalProperties",
              false),
          ToolRisk.READ_ONLY,
          "skill-read-only-v1");

  private final SkillCatalogPort catalog;
  private final int maxReadCodePoints;

  public ReadSkillTool(SkillCatalogPort catalog, int maxReadCodePoints) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    if (maxReadCodePoints < 1 || maxReadCodePoints > SkillContent.MAX_BODY_CODE_POINTS) {
      throw new IllegalArgumentException("Skill 正文读取预算无效");
    }
    this.maxReadCodePoints = maxReadCodePoints;
  }

  @Override
  public ToolDefinition definition() {
    return DEFINITION;
  }

  @Override
  public ToolResult execute(Map<String, Object> arguments) {
    String name = name(arguments);
    if (name == null) {
      return error(SkillContentToolError.SKILL_CONTENT_INVALID_ARGUMENT);
    }
    try {
      Optional<SkillContent> content = catalog.readAvailable(name);
      if (content.isEmpty()) {
        return error(SkillContentToolError.SKILL_CONTENT_UNAVAILABLE);
      }
      SkillContent resolved = content.orElseThrow();
      if (!name.equals(resolved.name())) {
        return error(SkillContentToolError.SKILL_CONTENT_UNAVAILABLE);
      }
      String body = resolved.body();
      if (body.codePointCount(0, body.length()) > maxReadCodePoints) {
        return error(SkillContentToolError.SKILL_CONTENT_BUDGET_EXCEEDED);
      }
      return ToolResult.success(body);
    } catch (RuntimeException unavailable) {
      return error(SkillContentToolError.SKILL_CONTENT_UNAVAILABLE);
    }
  }

  private static String name(Map<String, Object> arguments) {
    if (arguments == null || !arguments.keySet().equals(Set.of("name"))) {
      return null;
    }
    Object candidate = arguments.get("name");
    if (!(candidate instanceof String value) || !SkillDescriptor.isValidName(value)) {
      return null;
    }
    return value;
  }

  private static ToolResult error(SkillContentToolError error) {
    return ToolResult.error(error.name());
  }
}
