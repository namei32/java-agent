package io.namei.agent.kernel.tool;

import java.util.Map;
import java.util.Objects;

/**
 * 模型生成的一次标准化工具调用。
 *
 * @param id 单次模型响应内唯一的调用标识，用于关联 Tool Result
 * @param name 目标工具名称
 * @param arguments 解析后并深度不可变的 JSON 对象参数
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {
  public ToolCall {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    id = id.strip();
    name = name.strip();
    if (id.isBlank()) {
      throw new IllegalArgumentException("Tool Call ID 不能为空");
    }
    if (name.isBlank()) {
      throw new IllegalArgumentException("Tool Call 名称不能为空");
    }
    arguments = JsonValues.immutableObject(arguments);
  }
}
