package io.namei.agent.adapter.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

record McpRemoteTool(
    String name, String description, Map<String, Object> inputSchema, Boolean remoteReadOnlyHint) {
  McpRemoteTool {
    if (inputSchema != null) {
      inputSchema = Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
    }
  }
}
