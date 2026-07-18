package io.namei.agent.application;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolResult;
import java.util.Map;

/**
 * Internal-only extension point for built-in tools that need an explicit, turn-bound context.
 *
 * <p>This type is deliberately package-private: external plugins and MCP tools continue to see only
 * {@link Tool#execute(Map)} and cannot opt into a conversation scope.
 */
interface ContextualTool extends Tool {
  ToolResult execute(Map<String, Object> arguments, ToolInvocationContext context);
}
