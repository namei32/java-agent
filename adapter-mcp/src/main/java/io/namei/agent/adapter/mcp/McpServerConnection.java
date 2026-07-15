package io.namei.agent.adapter.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import io.namei.agent.kernel.port.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

final class McpServerConnection implements McpToolInvoker, AutoCloseable {
  private final AtomicReference<State> state = new AtomicReference<>(State.STARTING);
  private final McpStdioClient client;
  private final List<Tool> tools;

  McpServerConnection(McpServerDefinition definition, McpSettings settings) {
    Objects.requireNonNull(definition, "definition");
    Objects.requireNonNull(settings, "settings");
    McpStdioClient created = new McpStdioClient(definition, settings, this::observe);
    this.client = created;
    try {
      McpToolProjector projector = new McpToolProjector(settings.maxSchemaBytes());
      List<Tool> projected = new ArrayList<>();
      for (McpRemoteTool remote : created.initializeAndDiscover()) {
        projector
            .project(definition, remote)
            .ifPresent(tool -> projected.add(new McpToolAdapter(tool, this)));
      }
      this.tools = List.copyOf(projected);
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

  State state() {
    return state.get();
  }

  @Override
  public boolean available() {
    return state.get() == State.READY;
  }

  @Override
  public McpCallOutcome call(String remoteName, Map<String, Object> arguments) {
    if (!available()) {
      return McpCallOutcome.unavailable();
    }
    McpCallOutcome outcome = client.call(remoteName, arguments);
    if (outcome.status() == McpCallOutcome.Status.UNAVAILABLE) {
      state.compareAndSet(State.READY, State.UNAVAILABLE);
    }
    return outcome;
  }

  @Override
  public void close() {
    State previous = state.getAndSet(State.CLOSED);
    if (previous != State.CLOSED) {
      client.close();
    }
  }

  private void observe(McpSchema.JSONRPCMessage message) {
    if (message instanceof McpSchema.JSONRPCNotification notification
        && McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED.equals(notification.method())) {
      state.getAndUpdate(
          current -> current == State.STARTING || current == State.READY ? State.STALE : current);
    }
  }

  enum State {
    STARTING,
    READY,
    STALE,
    UNAVAILABLE,
    CLOSED
  }
}
