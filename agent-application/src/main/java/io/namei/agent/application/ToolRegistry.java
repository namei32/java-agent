package io.namei.agent.application;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ToolRegistry {
  private final Map<String, Tool> tools;
  private final List<ToolDefinition> definitions;

  ToolRegistry(List<Tool> tools) {
    this(tools, ToolRuntimeSettings.readOnlyDefaults());
  }

  ToolRegistry(List<Tool> tools, ToolRuntimeSettings settings) {
    Objects.requireNonNull(tools, "tools");
    Objects.requireNonNull(settings, "settings");
    if (settings.mode() == ToolRuntimeMode.DISABLED) {
      this.tools = Map.of();
      this.definitions = List.of();
      return;
    }
    var registered = new LinkedHashMap<String, Tool>();
    for (Tool tool : tools) {
      Objects.requireNonNull(tool, "tool");
      var definition = Objects.requireNonNull(tool.definition(), "tool.definition");
      if (registered.putIfAbsent(definition.name(), tool) != null) {
        throw new IllegalArgumentException("工具名称重复: " + definition.name());
      }
    }
    this.tools = Map.copyOf(registered);
    this.definitions = registered.values().stream().map(Tool::definition).toList();
  }

  List<ToolDefinition> definitions() {
    return definitions;
  }

  ToolResult execute(ToolCall call) {
    var tool = tools.get(call.name());
    if (tool == null) {
      return ToolResult.error("工具不可用。");
    }
    try {
      var result = tool.execute(call.arguments());
      return result == null ? ToolResult.error("工具执行失败。") : result;
    } catch (RuntimeException ignored) {
      return ToolResult.error("工具执行失败。");
    }
  }
}
