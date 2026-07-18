package io.namei.agent.adapter.mcp;

import io.namei.agent.kernel.mcp.McpAssetCatalog;
import io.namei.agent.kernel.port.Tool;
import java.util.List;

/** Public adapter boundary for a configured MCP runtime. */
public interface McpRuntime extends AutoCloseable {
  List<Tool> tools();

  default McpAssetCatalog assets() {
    return McpAssetCatalog.empty();
  }

  McpRuntimeStatus status();

  @Override
  void close();
}
