package io.namei.agent.bootstrap.control;

import io.namei.agent.kernel.control.ControlStableCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class ControlPlaneShutdownCoordinator implements AutoCloseable {
  private final ControlPlaneRuntime runtime;
  private final OperatorSessionStore sessions;
  private final ControlStreamTracker streams;
  private final ControlPlaneProperties properties;
  private final ControlPlaneAudit audit;
  private final Clock clock;
  private final AtomicBoolean closed = new AtomicBoolean();

  ControlPlaneShutdownCoordinator(
      ControlPlaneRuntime runtime,
      OperatorSessionStore sessions,
      ControlStreamTracker streams,
      ControlPlaneProperties properties,
      ControlPlaneAudit audit,
      Clock clock) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.streams = Objects.requireNonNull(streams, "streams");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    Instant startedAt = clock.instant();
    streams.stopAccepting();
    sessions.close();
    runtime.close();
    if (!streams.awaitDrained(properties.shutdownTimeout())) {
      audit.record(
          "CONTROL_SHUTDOWN",
          "TIMED_OUT",
          ControlStableCode.CONTROL_SHUTDOWN_TIMEOUT,
          "control-shutdown",
          null,
          null,
          streams.activeCount(),
          elapsedMillis(startedAt, clock.instant()));
    }
  }

  private static long elapsedMillis(Instant startedAt, Instant finishedAt) {
    Duration elapsed = Duration.between(startedAt, finishedAt);
    return elapsed.isNegative() ? 0 : elapsed.toMillis();
  }

  @Override
  public String toString() {
    return "ControlPlaneShutdownCoordinator[closed=" + closed.get() + "]";
  }
}
