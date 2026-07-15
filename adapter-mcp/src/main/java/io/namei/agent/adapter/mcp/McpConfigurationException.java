package io.namei.agent.adapter.mcp;

public final class McpConfigurationException extends RuntimeException {
  McpConfigurationException() {
    super("MCP 静态配置无效。", null, false, false);
  }
}
