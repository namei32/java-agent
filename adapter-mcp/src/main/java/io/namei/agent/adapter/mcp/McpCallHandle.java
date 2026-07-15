package io.namei.agent.adapter.mcp;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class McpCallHandle {
  private final BoundedStdioClientTransport transport;
  private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
  private final AtomicReference<Object> requestId = new AtomicReference<>();
  private final AtomicBoolean written = new AtomicBoolean();
  private final AtomicBoolean cancellationSent = new AtomicBoolean();

  McpCallHandle(BoundedStdioClientTransport transport) {
    this.transport = Objects.requireNonNull(transport, "transport");
  }

  void bind(Object id) {
    Objects.requireNonNull(id, "id");
    if (!requestId.compareAndSet(null, id)) {
      throw new IllegalStateException("MCP 调用关联失败");
    }
  }

  void markWritten() {
    written.set(true);
    trySendCancellation();
  }

  boolean complete() {
    return state.compareAndSet(State.OPEN, State.COMPLETED);
  }

  void cancel() {
    state.compareAndSet(State.OPEN, State.CANCELLED);
    trySendCancellation();
  }

  boolean isCancelled() {
    return state.get() == State.CANCELLED;
  }

  private void trySendCancellation() {
    Object id = requestId.get();
    if (state.get() == State.CANCELLED
        && written.get()
        && id != null
        && cancellationSent.compareAndSet(false, true)) {
      transport.trySendCancellation(id);
    }
  }

  private enum State {
    OPEN,
    CANCELLED,
    COMPLETED
  }
}
