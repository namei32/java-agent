package io.namei.agent.kernel.tool;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record ToolDefinition(
    String name, String description, Map<String, Object> inputSchema, ToolRisk risk) {
  private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  public ToolDefinition {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(risk, "risk");
    name = name.strip();
    description = description.strip();
    if (!VALID_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("工具名称无效");
    }
    if (description.isBlank()) {
      throw new IllegalArgumentException("工具说明不能为空");
    }
    inputSchema = JsonValues.immutableObject(inputSchema);
    if (!"object".equals(inputSchema.get("type"))) {
      throw new IllegalArgumentException("工具 inputSchema 顶层类型必须是 object");
    }
    if (risk != ToolRisk.READ_ONLY) {
      throw new IllegalArgumentException("第一阶段只允许 READ_ONLY 工具");
    }
  }
}
