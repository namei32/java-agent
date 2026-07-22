package io.namei.agent.adapter.mcp;

import io.namei.agent.kernel.mcp.McpAssetCatalog;
import java.util.List;

/** 单个已初始化 Server 的快照；资产元数据绝不包含 Resource 或 Prompt 正文。 */
record McpStartupCatalog(List<McpRemoteTool> tools, McpAssetCatalog assets) {}
