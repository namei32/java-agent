package io.namei.agent.adapter.mcp;

import io.namei.agent.kernel.mcp.McpAssetCatalog;
import io.namei.agent.kernel.mcp.McpAssetCatalogMode;
import io.namei.agent.kernel.port.Tool;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 静态只读 MCP 配置的默认运行时实现。
 *
 * <p>启动时逐个隔离连接已配置 Server，仅发布初始化成功且名称不冲突的工具；单个 Server 失败会使总体状态降级，但不会阻止其他 Server 可用。关闭操作通过原子生命周期和
 * Latch 保证幂等，并等待并发关闭者完成。
 */
final class DefaultMcpRuntime implements McpRuntime {
  private final int configuredServers;
  private final int startupFailures;
  private final List<McpServerConnection> connections;
  private final List<Tool> tools;
  private final AtomicReference<Lifecycle> lifecycle = new AtomicReference<>(Lifecycle.RUNNING);
  private final CountDownLatch closed = new CountDownLatch(1);

  DefaultMcpRuntime(McpConfiguration configuration, McpSettings settings) {
    this(configuration, settings, McpAssetCatalogMode.DISABLED);
  }

  DefaultMcpRuntime(
      McpConfiguration configuration, McpSettings settings, McpAssetCatalogMode assetsMode) {
    Objects.requireNonNull(configuration, "configuration");
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(assetsMode, "assetsMode");
    validate(configuration, settings);
    this.configuredServers = configuration.servers().size();

    List<McpServerConnection> readyConnections = new ArrayList<>();
    List<Tool> publishedTools = new ArrayList<>();
    Set<String> localNames = new HashSet<>();
    int failures = 0;
    for (McpServerDefinition server : configuration.servers()) {
      McpServerConnection connection = null;
      try {
        connection = new McpServerConnection(server, settings, assetsMode);
        Set<String> serverNames = new HashSet<>();
        for (Tool tool : connection.tools()) {
          String name = tool.definition().name();
          if (!serverNames.add(name) || localNames.contains(name)) {
            throw new McpClientException();
          }
        }
        localNames.addAll(serverNames);
        readyConnections.add(connection);
        publishedTools.addAll(connection.tools());
      } catch (RuntimeException exception) {
        failures++;
        if (connection != null) {
          connection.close();
        }
      }
    }
    this.startupFailures = failures;
    this.connections = List.copyOf(readyConnections);
    this.tools = List.copyOf(publishedTools);
  }

  /** 返回启动成功的所有 MCP 工具只读快照。 */
  @Override
  public List<Tool> tools() {
    return tools;
  }

  /** 聚合各活动连接已经发现的 MCP Resource、Prompt 等只读资产。 */
  @Override
  public McpAssetCatalog assets() {
    return new McpAssetCatalog(
        connections.stream()
            .flatMap(connection -> connection.assets().descriptors().stream())
            .toList());
  }

  /** 根据连接状态实时计算 READY、DEGRADED、CLOSING 或 CLOSED 汇总状态。 */
  @Override
  public McpRuntimeStatus status() {
    Lifecycle current = lifecycle.get();
    if (current == Lifecycle.CLOSING) {
      return new McpRuntimeStatus(McpRuntimeStatus.State.CLOSING, configuredServers, 0, 0, 0);
    }
    if (current == Lifecycle.CLOSED) {
      return new McpRuntimeStatus(McpRuntimeStatus.State.CLOSED, configuredServers, 0, 0, 0);
    }
    int ready = 0;
    int stale = 0;
    int unavailable = startupFailures;
    for (McpServerConnection connection : connections) {
      switch (connection.state()) {
        case READY -> ready++;
        case STALE -> stale++;
        case STARTING, RECONNECTING, UNAVAILABLE, CLOSED -> unavailable++;
      }
    }
    McpRuntimeStatus.State aggregate =
        stale == 0 && unavailable == 0
            ? McpRuntimeStatus.State.READY
            : McpRuntimeStatus.State.DEGRADED;
    return new McpRuntimeStatus(aggregate, configuredServers, ready, stale, unavailable);
  }

  /** 幂等关闭所有 Server 连接；单个连接清理失败不会阻断其余连接。 */
  @Override
  public void close() {
    if (!lifecycle.compareAndSet(Lifecycle.RUNNING, Lifecycle.CLOSING)) {
      awaitClosed();
      return;
    }
    try {
      for (McpServerConnection connection : connections) {
        try {
          connection.close();
        } catch (RuntimeException ignored) {
          // 单个隔离 Server 关闭失败不能阻止其余 Server 清理。
        }
      }
    } finally {
      lifecycle.set(Lifecycle.CLOSED);
      closed.countDown();
    }
  }

  private void awaitClosed() {
    try {
      closed.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private static void validate(McpConfiguration configuration, McpSettings settings) {
    if (settings.mode() != McpMode.STATIC_READ_ONLY
        || configuration.servers().size() > settings.maxServers()) {
      throw new IllegalArgumentException("MCP Runtime 配置无效");
    }
    Set<String> ids = new HashSet<>();
    for (McpServerDefinition server : configuration.servers()) {
      if (server == null || !ids.add(server.id())) {
        throw new IllegalArgumentException("MCP Runtime 配置无效");
      }
    }
  }

  private enum Lifecycle {
    RUNNING,
    CLOSING,
    CLOSED
  }
}
