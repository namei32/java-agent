package io.namei.agent.adapter.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import io.namei.agent.kernel.mcp.McpAssetCatalog;
import io.namei.agent.kernel.mcp.McpAssetCatalogMode;
import io.namei.agent.kernel.port.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

final class McpServerConnection implements McpToolInvoker, AutoCloseable {
  private final McpServerDefinition definition;
  private final McpSettings settings;
  private final McpAssetCatalogMode assetsMode;
  private final McpToolProjector projector;
  private final AtomicReference<State> state = new AtomicReference<>(State.STARTING);
  private final AtomicReference<McpStdioClient> client = new AtomicReference<>();
  private final ReentrantLock reconnectLock = new ReentrantLock(true);
  private final String catalogFingerprint;
  private final AtomicReference<McpAssetCatalog> assets =
      new AtomicReference<>(McpAssetCatalog.empty());
  private final List<Tool> tools;

  McpServerConnection(McpServerDefinition definition, McpSettings settings) {
    this(definition, settings, McpAssetCatalogMode.DISABLED);
  }

  McpServerConnection(
      McpServerDefinition definition, McpSettings settings, McpAssetCatalogMode assetsMode) {
    this.definition = Objects.requireNonNull(definition, "definition");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.assetsMode = Objects.requireNonNull(assetsMode, "assetsMode");
    this.projector = new McpToolProjector(settings.maxSchemaBytes());
    McpStdioClient created = newClient();
    try {
      McpConnectionCatalog discovered = discover(created);
      List<McpProjectedTool> projected = discovered.tools();
      this.catalogFingerprint = McpCatalogFingerprint.of(projected);
      this.tools = projected.stream().map(tool -> (Tool) new McpToolAdapter(tool, this)).toList();
      this.assets.set(discovered.assets());
      client.set(created);
      state.compareAndSet(State.STARTING, State.READY);
    } catch (RuntimeException exception) {
      state.set(State.UNAVAILABLE);
      created.close();
      throw new McpClientException();
    }
  }

  List<Tool> tools() {
    return tools;
  }

  McpAssetCatalog assets() {
    return state.get() == State.READY ? assets.get() : McpAssetCatalog.empty();
  }

  State state() {
    return state.get();
  }

  @Override
  public boolean available() {
    State current = state.get();
    return current == State.READY || current == State.UNAVAILABLE;
  }

  @Override
  public McpCallOutcome call(String remoteName, Map<String, Object> arguments) {
    State current = state.get();
    if (current == State.UNAVAILABLE && !reconnectOnce()) {
      return McpCallOutcome.unavailable();
    }
    if (state.get() != State.READY) {
      return McpCallOutcome.unavailable();
    }
    McpStdioClient active = client.get();
    if (active == null) {
      state.compareAndSet(State.READY, State.UNAVAILABLE);
      return McpCallOutcome.unavailable();
    }
    McpCallOutcome outcome = active.call(remoteName, arguments);
    if (outcome.status() == McpCallOutcome.Status.UNAVAILABLE) {
      state.compareAndSet(State.READY, State.UNAVAILABLE);
      return outcome;
    }
    return state.get() == State.READY ? outcome : McpCallOutcome.unavailable();
  }

  @Override
  public void close() {
    State previous = state.getAndSet(State.CLOSED);
    if (previous == State.CLOSED) {
      return;
    }
    reconnectLock.lock();
    try {
      McpStdioClient active = client.getAndSet(null);
      if (active != null) {
        active.close();
      }
    } finally {
      reconnectLock.unlock();
    }
  }

  private boolean reconnectOnce() {
    boolean acquired = false;
    McpStdioClient replacement = null;
    try {
      acquired = reconnectLock.tryLock(settings.connectTimeout().toNanos(), TimeUnit.NANOSECONDS);
      if (!acquired) {
        return false;
      }
      if (state.get() == State.READY) {
        return true;
      }
      if (!state.compareAndSet(State.UNAVAILABLE, State.RECONNECTING)) {
        return false;
      }
      replacement = newClient();
      McpConnectionCatalog discovered = discover(replacement);
      if (!catalogFingerprint.equals(McpCatalogFingerprint.of(discovered.tools()))
          || !assets.get().equals(discovered.assets())) {
        state.compareAndSet(State.RECONNECTING, State.STALE);
        replacement.close();
        return false;
      }
      McpStdioClient previous = client.getAndSet(replacement);
      if (!state.compareAndSet(State.RECONNECTING, State.READY)) {
        client.compareAndSet(replacement, previous);
        replacement.close();
        return false;
      }
      assets.set(discovered.assets());
      replacement = null;
      if (previous != null) {
        previous.close();
      }
      return true;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new McpCallCancelledException();
    } catch (RuntimeException exception) {
      state.compareAndSet(State.RECONNECTING, State.UNAVAILABLE);
      if (replacement != null) {
        replacement.close();
      }
      return false;
    } finally {
      if (acquired) {
        reconnectLock.unlock();
      }
    }
  }

  private McpStdioClient newClient() {
    return new McpStdioClient(definition, settings, this::observe);
  }

  private McpConnectionCatalog discover(McpStdioClient candidate) {
    List<McpProjectedTool> projected = new ArrayList<>();
    McpStartupCatalog catalog = candidate.initializeAndDiscover(assetsMode, definition.id());
    for (McpRemoteTool remote : catalog.tools()) {
      projector.project(definition, remote).ifPresent(projected::add);
    }
    return new McpConnectionCatalog(List.copyOf(projected), catalog.assets());
  }

  private void observe(McpSchema.JSONRPCMessage message) {
    if (message instanceof McpSchema.JSONRPCNotification notification
        && (McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED.equals(notification.method())
            || (assetsMode == McpAssetCatalogMode.CATALOG_ONLY
                && ("notifications/resources/list_changed".equals(notification.method())
                    || "notifications/prompts/list_changed".equals(notification.method()))))) {
      state.getAndUpdate(
          current ->
              current == State.STARTING || current == State.READY || current == State.RECONNECTING
                  ? State.STALE
                  : current);
    }
  }

  enum State {
    STARTING,
    READY,
    RECONNECTING,
    STALE,
    UNAVAILABLE,
    CLOSED
  }
}
