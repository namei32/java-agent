package io.namei.agent.adapter.mcp;

import java.util.List;
import java.util.Objects;

/**
 * 解析完成的版本化 MCP Server 配置。
 *
 * @param schemaVersion 配置 Schema 版本，当前只接受 1
 * @param servers 需要启动的 Server 定义只读列表
 */
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
