package io.namei.agent.adapter.mcp;

import io.namei.agent.kernel.tool.ToolDefinition;
import java.util.Objects;

record McpProjectedTool(String remoteName, ToolDefinition definition) {
  McpProjectedTool {
    Objects.requireNonNull(remoteName, "remoteName");
    Objects.requireNonNull(definition, "definition");
  }
}
