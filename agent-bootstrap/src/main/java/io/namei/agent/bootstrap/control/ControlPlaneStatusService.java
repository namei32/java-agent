package io.namei.agent.bootstrap.control;

import io.namei.agent.application.control.ActiveTurnRegistrySnapshot;
import io.namei.agent.application.control.ControlCancellationOutcome;
import io.namei.agent.application.control.ControlEventHubSnapshot;
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
import java.util.regex.Pattern;

public final class ControlPlaneStatusService {
  private static final int DEFAULT_INDEX_PAGE_SIZE = 20;
  private static final int MAXIMUM_INDEX_PAGE_SIZE = 50;
  private static final Pattern INDEX_CURSOR = Pattern.compile("(?:|[A-Za-z0-9_-]{22})");

  private final Clock clock;
  private final ChannelHost host;
  private final ControlPlaneRuntime runtime;
  private final ControlPlaneProperties properties;
  private final ControlIndexCursorStore indexCursors;

  public ControlPlaneStatusService(
      Clock clock,
      ChannelHost host,
      ControlPlaneRuntime runtime,
      ControlPlaneProperties properties) {
    this(
        clock,
        host,
        runtime,
        properties,
        new ControlIndexCursorStore(clock, ControlRandomSource.secure()));
  }

  ControlPlaneStatusService(
      Clock clock,
      ChannelHost host,
      ControlPlaneRuntime runtime,
      ControlPlaneProperties properties,
      ControlIndexCursorStore indexCursors) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.host = Objects.requireNonNull(host, "host");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.indexCursors = Objects.requireNonNull(indexCursors, "indexCursors");
  }

  public ControlStatusResponse status() {
    Instant observedAt = clock.instant();
    ActiveTurnRegistrySnapshot registry;
    List<ChannelStatusSnapshot> snapshots;
    ControlEventHubSnapshot events;
    try {
      registry = runtime.registry().snapshot();
      snapshots = host.snapshots();
      events = runtime.eventHub().snapshot();
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
            events.subscriberCount(),
            events.maxSubscriberQueueDepth(),
            events.subscriberBufferCapacity(),
            events.slowConsumerDisconnects()),
        channels);
  }

  public ControlTurnsResponse turns() {
    return ControlTurnsResponse.from(clock.instant(), runtime.registry().snapshot());
  }

  public ControlIndexResponse index(int pageSize, String cursor, String actorRef) {
    requireIndexRequest(pageSize, cursor);
    Instant observedAt = clock.instant();
    ActiveTurnRegistrySnapshot registry;
    List<ChannelStatusSnapshot> snapshots;
    try {
      registry = runtime.registry().snapshot();
      snapshots = host.snapshots();
    } catch (RuntimeException unavailable) {
      return indexUnavailable(observedAt);
    }
    if (snapshots.stream().anyMatch(ControlPlaneStatusService::snapshotUnavailable)) {
      return indexUnavailable(observedAt);
    }
    List<ControlIndexResponse.Channel> channels =
        snapshots.stream()
            .sorted(Comparator.comparing(ChannelStatusSnapshot::name))
            .map(snapshot -> indexChannel(snapshot, registry))
            .toList();
    List<ControlIndexResponse.Turn> candidates;
    if (cursor.isEmpty()) {
      candidates =
          registry.activeTurns().stream()
              .map(ControlPlaneStatusService::indexTurn)
              .sorted(Comparator.comparing(ControlIndexResponse.Turn::turnRef))
              .toList();
    } else {
      candidates =
          indexCursors
              .take(cursor, actorRef)
              .orElseThrow(() -> new IllegalArgumentException("控制索引游标无效或已失效"));
    }
    int end = Math.min(pageSize, candidates.size());
    List<ControlIndexResponse.Turn> turns = candidates.subList(0, end);
    String nextCursor = indexCursors.issue(actorRef, candidates.subList(end, candidates.size()));
    IndexState state = indexState(registry);
    return new ControlIndexResponse(
        ControlPlaneContract.CURRENT_VERSION,
        observedAt,
        state.state(),
        state.code(),
        channels,
        turns,
        nextCursor);
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

  private static ControlIndexResponse indexUnavailable(Instant observedAt) {
    return new ControlIndexResponse(
        ControlPlaneContract.CURRENT_VERSION,
        observedAt,
        "DEGRADED",
        ControlStableCode.CONTROL_SNAPSHOT_UNAVAILABLE.name(),
        List.of(),
        List.of(),
        "");
  }

  private ControlIndexResponse.Channel indexChannel(
      ChannelStatusSnapshot snapshot, ActiveTurnRegistrySnapshot registry) {
    int activeTurns =
        Math.toIntExact(
            registry.activeTurns().stream()
                .filter(turn -> snapshot.name().equals(turn.channel()))
                .count());
    return new ControlIndexResponse.Channel(
        snapshot.name(),
        snapshot.state().name(),
        activeTurns,
        snapshot.reliability().unknownExecutions());
  }

  private static ControlIndexResponse.Turn indexTurn(
      io.namei.agent.application.control.ActiveTurnSnapshot snapshot) {
    return new ControlIndexResponse.Turn(
        snapshot.turnRef().value(),
        snapshot.channel(),
        snapshot.state().name(),
        snapshot.lastSequence());
  }

  private IndexState indexState(ActiveTurnRegistrySnapshot registry) {
    if (runtime.isClosed()) {
      return new IndexState("SHUTTING_DOWN", ControlStableCode.CONTROL_SHUTTING_DOWN.name());
    }
    if (registry.saturated()) {
      return new IndexState("DEGRADED", ControlStableCode.CONTROL_TURN_REGISTRY_SATURATED.name());
    }
    return new IndexState("READY", "");
  }

  private static void requireIndexRequest(int pageSize, String cursor) {
    if (pageSize < 1 || pageSize > MAXIMUM_INDEX_PAGE_SIZE) {
      throw new IllegalArgumentException("控制索引分页大小无效");
    }
    if (cursor == null || !INDEX_CURSOR.matcher(cursor).matches()) {
      throw new IllegalArgumentException("控制索引游标格式无效");
    }
  }

  static int defaultIndexPageSize() {
    return DEFAULT_INDEX_PAGE_SIZE;
  }

  private record IndexState(String state, String code) {}

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
