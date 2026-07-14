package io.namei.agent.application;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class ToolRegistry {
  private final Map<String, Tool> tools;
  private final Map<String, ToolSchemaValidator> validators;
  private final List<ToolDefinition> definitions;
  private final ToolRuntimeSettings settings;

  ToolRegistry(List<Tool> tools) {
    this(tools, ToolRuntimeSettings.readOnlyDefaults());
  }

  ToolRegistry(List<Tool> tools, ToolRuntimeSettings settings) {
    Objects.requireNonNull(tools, "tools");
    this.settings = Objects.requireNonNull(settings, "settings");
    if (settings.mode() == ToolRuntimeMode.DISABLED) {
      this.tools = Map.of();
      this.validators = Map.of();
      this.definitions = List.of();
      return;
    }
    var registered = new LinkedHashMap<String, Tool>();
    var registeredValidators = new LinkedHashMap<String, ToolSchemaValidator>();
    for (Tool tool : tools) {
      Objects.requireNonNull(tool, "tool");
      var definition = Objects.requireNonNull(tool.definition(), "tool.definition");
      if (registered.putIfAbsent(definition.name(), tool) != null) {
        throw new IllegalArgumentException("工具名称重复: " + definition.name());
      }
      registeredValidators.put(
          definition.name(), new ToolSchemaValidator(definition.inputSchema()));
    }
    this.tools = Map.copyOf(registered);
    this.validators = Map.copyOf(registeredValidators);
    this.definitions = registered.values().stream().map(Tool::definition).toList();
  }

  List<Optional<ToolResult>> preflight(List<ToolCall> calls) {
    return calls.stream()
        .map(
            call -> {
              var validator = validators.get(call.name());
              if (validator != null && !validator.accepts(call.arguments())) {
                return Optional.of(ToolResult.error("工具参数无效。"));
              }
              return Optional.<ToolResult>empty();
            })
        .toList();
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
      if (result == null) {
        return ToolResult.error("工具执行失败。");
      }
      if (result.content().codePointCount(0, result.content().length())
          > settings.maxResultCharacters()) {
        return ToolResult.error("工具结果超过大小限制。");
      }
      return result;
    } catch (RuntimeException ignored) {
      return ToolResult.error("工具执行失败。");
    }
  }
}
