package io.namei.agent.kernel.port;

import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import java.util.Map;

public interface Tool {
  ToolDefinition definition();

  ToolResult execute(Map<String, Object> arguments);
}
