package io.namei.agent.kernel.tool;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record ToolDefinition(
    String name,
    String description,
    Map<String, Object> inputSchema,
    ToolRisk risk,
    String version) {
  private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern VALID_VERSION = Pattern.compile("[A-Za-z0-9._-]{1,32}");

  public ToolDefinition(
      String name, String description, Map<String, Object> inputSchema, ToolRisk risk) {
    this(name, description, inputSchema, risk, "v1");
  }

  public ToolDefinition {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(risk, "risk");
    Objects.requireNonNull(version, "version");
    name = name.strip();
    description = description.strip();
    version = version.strip();
    if (!VALID_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("工具名称无效");
    }
    if (description.isBlank()) {
      throw new IllegalArgumentException("工具说明不能为空");
    }
    if (!VALID_VERSION.matcher(version).matches()) {
      throw new IllegalArgumentException("工具版本无效");
    }
    inputSchema = JsonValues.immutableObject(inputSchema);
    if (!"object".equals(inputSchema.get("type"))) {
      throw new IllegalArgumentException("工具 inputSchema 顶层类型必须是 object");
    }
  }
}
