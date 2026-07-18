package io.namei.agent.bootstrap.control;

import io.namei.agent.application.control.ActiveTurnObserver;
import io.namei.agent.application.control.ActiveTurnRegistration;
import io.namei.agent.application.control.ActiveTurnRegistry;
import io.namei.agent.application.control.ControlCancellationHandle;
import io.namei.agent.application.control.ControlEventHub;
import io.namei.agent.application.control.ControlTurnRefGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ControlPlaneRuntime implements ActiveTurnObserver, AutoCloseable {
  private final ActiveTurnRegistry registry;
  private final ControlEventHub eventHub;
  private final AtomicBoolean closed = new AtomicBoolean();

  ControlPlaneRuntime(
      ControlPlaneProperties properties, Clock clock, ControlTurnRefGenerator references) {
    Objects.requireNonNull(properties, "properties");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(references, "references");
    if (properties.mode() != ControlPlaneMode.LOOPBACK) {
      throw new IllegalArgumentException("控制面 Runtime 只允许 LOOPBACK 模式");
    }
    registry =
        new ActiveTurnRegistry(
            clock,
            references,
            properties.maxActiveTurns(),
            properties.terminalRetention(),
            properties.maxTerminalTombstones());
    eventHub =
        new ControlEventHub(
            registry,
            clock,
            properties.maxSubscribers(),
            properties.subscriberBufferCapacity(),
            properties.streamMaxLifetime());
  }

  @Override
  public ActiveTurnRegistration register(
      String channel, ControlCancellationHandle cancellation, Instant startedAt) {
    return registry.register(channel, cancellation, startedAt);
  }

  public ActiveTurnRegistry registry() {
    return registry;
  }

  public ControlEventHub eventHub() {
    return eventHub;
  }

  public boolean isClosed() {
    return closed.get();
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      registry.close();
    }
  }

  @Override
  public String toString() {
    return "ControlPlaneRuntime[closed=" + closed.get() + ", sensitiveFields=<redacted>]";
  }
}
