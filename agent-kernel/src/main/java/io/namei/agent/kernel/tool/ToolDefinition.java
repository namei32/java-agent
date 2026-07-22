package io.namei.agent.kernel.tool;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 模型可见的工具契约定义。
 *
 * @param name 稳定工具名称，只允许安全字符且最长 64 个字符
 * @param description 帮助模型判断调用时机的非空说明
 * @param inputSchema 顶层类型必须为 object 的不可变 JSON Schema
 * @param risk 工具声明的副作用风险等级
 * @param version 参与审批指纹和兼容判断的工具版本
 */
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
