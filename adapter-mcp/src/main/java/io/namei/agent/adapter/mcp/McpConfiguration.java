package io.namei.agent.adapter.mcp;

import java.util.List;
import java.util.Objects;

public record McpConfiguration(int schemaVersion, List<McpServerDefinition> servers) {
  public McpConfiguration {
    if (schemaVersion != 1) {
      throw new IllegalArgumentException("不支持的 MCP 配置版本");
    }
    servers = List.copyOf(Objects.requireNonNull(servers, "servers"));
  }

  static McpConfiguration disabled() {
    return new McpConfiguration(1, List.of());
  }
}
