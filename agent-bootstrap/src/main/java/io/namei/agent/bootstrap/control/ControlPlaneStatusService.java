package io.namei.agent.bootstrap.control;

import io.namei.agent.application.control.ActiveTurnRegistrySnapshot;
import io.namei.agent.application.control.ControlCancellationOutcome;
import io.namei.agent.bootstrap.channel.ChannelHost;
import io.namei.agent.bootstrap.channel.ChannelReliabilityStatus;
import io.namei.agent.bootstrap.channel.ChannelStatusSnapshot;
import io.namei.agent.kernel.control.ControlPlaneContract;
import io.namei.agent.kernel.control.ControlStableCode;
import io.namei.agent.kernel.control.ControlTurnRef;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class ControlPlaneStatusService {
  private final Clock clock;
  private final ChannelHost host;
  private final ControlPlaneRuntime runtime;
  private final ControlPlaneProperties properties;

  public ControlPlaneStatusService(
      Clock clock,
      ChannelHost host,
      ControlPlaneRuntime runtime,
      ControlPlaneProperties properties) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.host = Objects.requireNonNull(host, "host");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  public ControlStatusResponse status() {
    Instant observedAt = clock.instant();
    ActiveTurnRegistrySnapshot registry;
    List<ChannelStatusSnapshot> snapshots;
    try {
      registry = runtime.registry().snapshot();
      snapshots = host.snapshots();
    } catch (RuntimeException unavailable) {
      return unavailable(observedAt);
    }
    List<ControlStatusResponse.Channel> channels =
        snapshots.stream()
            .sorted(Comparator.comparing(ChannelStatusSnapshot::name))
            .map(ControlPlaneStatusService::channel)
            .toList();
    String state = "READY";
    String code = "";
    if (runtime.isClosed()) {
      state = "SHUTTING_DOWN";
      code = ControlStableCode.CONTROL_SHUTTING_DOWN.name();
    } else if (registry.saturated()) {
      state = "DEGRADED";
      code = ControlStableCode.CONTROL_TURN_REGISTRY_SATURATED.name();
    } else if (snapshots.stream().anyMatch(ControlPlaneStatusService::snapshotUnavailable)) {
      state = "DEGRADED";
      code = ControlStableCode.CONTROL_SNAPSHOT_UNAVAILABLE.name();
    }
    return new ControlStatusResponse(
        ControlPlaneContract.CURRENT_VERSION,
        observedAt,
        new ControlStatusResponse.Host(host.state().name(), ""),
        new ControlStatusResponse.Control(
            state,
            code,
            registry.activeTurns().size(),
            registry.terminalTombstones(),
            runtime.eventHub().subscriberCount(),
            0,
            properties.subscriberBufferCapacity(),
            0),
        channels);
  }

  public ControlTurnsResponse turns() {
    return ControlTurnsResponse.from(clock.instant(), runtime.registry().snapshot());
  }

  public ControlCancellationOutcome cancel(String reference) {
    return runtime.registry().cancel(ControlTurnRef.parse(reference));
  }

  private ControlStatusResponse unavailable(Instant observedAt) {
    return new ControlStatusResponse(
        ControlPlaneContract.CURRENT_VERSION,
        observedAt,
        new ControlStatusResponse.Host(host.state().name(), ""),
        new ControlStatusResponse.Control(
            "DEGRADED",
            ControlStableCode.CONTROL_SNAPSHOT_UNAVAILABLE.name(),
            0,
            0,
            0,
            0,
            properties.subscriberBufferCapacity(),
            0),
        List.of());
  }

  private static ControlStatusResponse.Channel channel(ChannelStatusSnapshot snapshot) {
    ChannelReliabilityStatus reliability = snapshot.reliability();
    return new ControlStatusResponse.Channel(
        snapshot.name(),
        snapshot.state().name(),
        snapshot.code(),
        snapshot.activeTurns(),
        snapshot.consecutiveFailures(),
        new ControlStatusResponse.Reliability(
            reliability.mode().name(),
            reliability.ledgerState().name(),
            reliability.pendingDeliveries(),
            reliability.unknownExecutions(),
            reliability.unknownDeliveries(),
            reliability.lastStableErrorCode()));
  }

  private static boolean snapshotUnavailable(ChannelStatusSnapshot snapshot) {
    return "SNAPSHOT_FAILED".equals(snapshot.code()) || "SNAPSHOT_INVALID".equals(snapshot.code());
  }
}
