package io.namei.agent.kernel.tool;

import java.util.Map;
import java.util.Objects;

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
