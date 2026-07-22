package io.namei.agent.adapter.mcp;

import io.namei.agent.kernel.mcp.McpAssetCatalog;
import io.namei.agent.kernel.port.Tool;
import java.util.List;

/**
 * 已配置 MCP Runtime 的公开适配器边界。
 *
 * <p>该接口只暴露核心 {@link Tool} 与 MCP 资产投影，刻意隐藏 MCP SDK、Reactor 和传输实现类型，使 bootstrap 可以在禁用和静态只读运行时之间安全切换。
 */
public interface McpRuntime extends AutoCloseable {
  /** 返回当前可供 Agent 注册的 MCP 工具只读快照。 */
  List<Tool> tools();

  /** 返回 MCP Server 已发现资产的安全投影；不支持资产时返回空目录。 */
  default McpAssetCatalog assets() {
    return McpAssetCatalog.empty();
  }

  /** 返回配置数量、可用连接和降级情况的运行时快照。 */
  McpRuntimeStatus status();

  /** 释放所有子进程、传输和后台任务；实现必须支持重复调用。 */
  @Override
  void close();
}
