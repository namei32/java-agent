package io.namei.agent.adapter.mcp;

final class McpCatalogException extends RuntimeException {
  McpCatalogException() {
    super("MCP Tool Catalog 无效", null, false, false);
  }
}
