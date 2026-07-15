package io.namei.agent.adapter.mcp;

import io.namei.agent.kernel.port.Tool;
import java.util.List;

/** Public adapter boundary for a configured MCP runtime. */
public interface McpRuntime extends AutoCloseable {
  List<Tool> tools();

  McpRuntimeStatus status();

  @Override
  void close();
}
