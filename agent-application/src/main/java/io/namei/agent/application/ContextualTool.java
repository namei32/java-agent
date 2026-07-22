package io.namei.agent.application;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolResult;
import java.util.Map;

/**
 * 供需要显式 Turn 绑定上下文的内置 Tool 使用的内部扩展点。
 *
 * <p>该类型有意保持包内可见：外部 Plugin 和 MCP Tool 仍只能看到 {@link Tool#execute(Map)}，无法选择加入会话 Scope。
 */
interface ContextualTool extends Tool {
  ToolResult execute(Map<String, Object> arguments, ToolInvocationContext context);
}
