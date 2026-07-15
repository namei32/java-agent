package io.namei.agent.adapter.mcp;

import java.util.Map;

interface McpToolInvoker {
  boolean available();

  McpCallOutcome call(String remoteName, Map<String, Object> arguments);
}
