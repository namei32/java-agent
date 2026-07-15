package io.namei.agent.adapter.mcp;

import io.namei.agent.kernel.tool.ToolRisk;
import java.util.Objects;

public record McpToolPolicy(boolean enabled, ToolRisk risk) {
  public McpToolPolicy {
    Objects.requireNonNull(risk, "risk");
    if (risk != ToolRisk.READ_ONLY) {
      throw new IllegalArgumentException("MCP 只允许 READ_ONLY Tool Policy");
    }
  }
}
