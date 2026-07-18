package io.namei.agent.adapter.mcp;

import io.namei.agent.kernel.mcp.McpAssetCatalog;
import java.util.List;

/** One initialized server snapshot; asset metadata never contains a Resource or Prompt body. */
record McpStartupCatalog(List<McpRemoteTool> tools, McpAssetCatalog assets) {}
