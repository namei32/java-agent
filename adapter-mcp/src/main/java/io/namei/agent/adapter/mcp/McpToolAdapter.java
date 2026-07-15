package io.namei.agent.adapter.mcp;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import java.util.Map;
import java.util.Objects;

final class McpToolAdapter implements Tool {
  private final String remoteName;
  private final ToolDefinition definition;
  private final McpToolInvoker invoker;

  McpToolAdapter(McpProjectedTool projected, McpToolInvoker invoker) {
    Objects.requireNonNull(projected, "projected");
    this.remoteName = projected.remoteName();
    this.definition = projected.definition();
    this.invoker = Objects.requireNonNull(invoker, "invoker");
  }

  @Override
  public ToolDefinition definition() {
    return definition;
  }

  @Override
  public ToolResult execute(Map<String, Object> arguments) {
    Objects.requireNonNull(arguments, "arguments");
    if (!invoker.available()) {
      return ToolResult.error("MCP 工具不可用。");
    }
    try {
      McpCallOutcome outcome = invoker.call(remoteName, arguments);
      return switch (outcome.status()) {
        case SUCCESS -> ToolResult.success(outcome.text());
        case REMOTE_ERROR -> ToolResult.error("MCP 工具执行失败。");
        case UNSUPPORTED_RESULT -> ToolResult.error("MCP 工具结果类型不受支持。");
        case TIMEOUT -> ToolResult.timeout();
        case UNAVAILABLE -> ToolResult.error("MCP 工具不可用。");
      };
    } catch (McpCallCancelledException exception) {
      return ToolResult.cancelled();
    } catch (RuntimeException exception) {
      return ToolResult.error("MCP 工具不可用。");
    }
  }
}
