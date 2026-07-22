package io.namei.agent.adapter.mcp;

import io.namei.agent.kernel.mcp.McpAssetCatalogMode;
import io.namei.agent.kernel.port.Tool;
import java.util.List;

/**
 * 不在公开边界暴露 SDK 类型的安全 Runtime 工厂。
 *
 * <p>调用方通过该工厂显式选择完全禁用或静态只读模式，避免自行构造内部连接对象并绕过配置校验。
 */
public final class McpRuntimes {
  private McpRuntimes() {}

  /** 创建无工具、无资产且无需清理资源的禁用运行时。 */
  public static McpRuntime disabled() {
    return DisabledRuntime.INSTANCE;
  }

  /** 使用关闭的资产目录创建静态只读运行时。 */
  public static McpRuntime staticReadOnly(McpConfiguration configuration, McpSettings settings) {
    return staticReadOnly(configuration, settings, McpAssetCatalogMode.DISABLED);
  }

  /**
   * 创建静态只读运行时，并显式指定 MCP 资产发现模式。
   *
   * @param configuration Server 可执行文件、参数、环境变量和工具风险配置
   * @param settings Server 数量、消息大小和超时等运行边界
   * @param assetsMode MCP 资产目录是否启用
   */
  public static McpRuntime staticReadOnly(
      McpConfiguration configuration, McpSettings settings, McpAssetCatalogMode assetsMode) {
    return new DefaultMcpRuntime(configuration, settings, assetsMode);
  }

  private enum DisabledRuntime implements McpRuntime {
    INSTANCE;

    @Override
    public List<Tool> tools() {
      return List.of();
    }

    @Override
    public McpRuntimeStatus status() {
      return McpRuntimeStatus.disabled();
    }

    @Override
    public void close() {}
  }
}
