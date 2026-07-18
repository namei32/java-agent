package io.namei.agent.adapter.mcp;

import io.namei.agent.kernel.mcp.McpAssetCatalog;
import java.util.List;

/** Server-local startup view after projecting untrusted remote tools and asset metadata. */
record McpConnectionCatalog(List<McpProjectedTool> tools, McpAssetCatalog assets) {}
