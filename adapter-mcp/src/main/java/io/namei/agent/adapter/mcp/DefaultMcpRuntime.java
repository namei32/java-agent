package io.namei.agent.adapter.mcp;

import io.namei.agent.kernel.port.Tool;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class DefaultMcpRuntime implements McpRuntime {
  private final int configuredServers;
  private final int startupFailures;
  private final List<McpServerConnection> connections;
  private final List<Tool> tools;
  private final AtomicReference<Lifecycle> lifecycle = new AtomicReference<>(Lifecycle.RUNNING);
  private final CountDownLatch closed = new CountDownLatch(1);

  DefaultMcpRuntime(McpConfiguration configuration, McpSettings settings) {
    Objects.requireNonNull(configuration, "configuration");
    Objects.requireNonNull(settings, "settings");
    validate(configuration, settings);
    this.configuredServers = configuration.servers().size();

    List<McpServerConnection> readyConnections = new ArrayList<>();
    List<Tool> publishedTools = new ArrayList<>();
    Set<String> localNames = new HashSet<>();
    int failures = 0;
    for (McpServerDefinition server : configuration.servers()) {
      McpServerConnection connection = null;
      try {
        connection = new McpServerConnection(server, settings);
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

  @Override
  public List<Tool> tools() {
    return tools;
  }

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
          // Closing one isolated server cannot prevent cleanup of the remaining servers.
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
