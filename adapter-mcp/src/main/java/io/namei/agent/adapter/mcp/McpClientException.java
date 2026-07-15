package io.namei.agent.adapter.mcp;

final class McpClientException extends RuntimeException {
  McpClientException() {
    super("MCP Server 不可用。", null, false, false);
  }
}
