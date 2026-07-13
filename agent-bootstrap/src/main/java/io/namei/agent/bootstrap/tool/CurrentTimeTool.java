package io.namei.agent.bootstrap.tool;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;

public final class CurrentTimeTool implements Tool {
  private static final ToolDefinition DEFINITION =
      new ToolDefinition(
          "current_time",
          "返回当前 UTC 时间",
          Map.of(
              "type", "object",
              "properties", Map.of(),
              "additionalProperties", false),
          ToolRisk.READ_ONLY);

  private final Clock clock;

  public CurrentTimeTool(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public ToolDefinition definition() {
    return DEFINITION;
  }

  @Override
  public ToolResult execute(Map<String, Object> arguments) {
    if (arguments == null || !arguments.isEmpty()) {
      return ToolResult.error("工具参数无效。");
    }
    return ToolResult.success(OffsetDateTime.now(clock).toString());
  }
}
