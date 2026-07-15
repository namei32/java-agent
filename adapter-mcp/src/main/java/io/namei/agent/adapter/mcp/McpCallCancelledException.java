package io.namei.agent.adapter.mcp;

final class McpCallCancelledException extends RuntimeException {
  McpCallCancelledException() {
    super("MCP 调用已取消。", null, false, false);
  }
}
