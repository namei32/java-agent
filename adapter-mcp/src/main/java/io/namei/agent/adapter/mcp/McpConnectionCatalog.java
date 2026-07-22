package io.namei.agent.adapter.mcp;

import io.namei.agent.kernel.mcp.McpAssetCatalog;
import java.util.List;

/** 投影不可信远程工具和资产元数据后形成的 Server 本地启动视图。 */
record McpConnectionCatalog(List<McpProjectedTool> tools, McpAssetCatalog assets) {}
