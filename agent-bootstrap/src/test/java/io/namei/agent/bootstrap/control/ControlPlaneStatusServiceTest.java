package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.TurnCancellationSource;
import io.namei.agent.application.control.ControlCancellationHandle;
import io.namei.agent.bootstrap.channel.ChannelAdapter;
import io.namei.agent.bootstrap.channel.ChannelHost;
import io.namei.agent.bootstrap.channel.ChannelReliabilityStatus;
import io.namei.agent.bootstrap.channel.ChannelState;
import io.namei.agent.bootstrap.channel.ChannelStatusSnapshot;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.control.ControlTurnRef;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ControlPlaneStatusServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

  @Test
  void exposesExactSafeStatusAndStableSortedActiveTurns() {
    var host =
        new ChannelHost(List.of(new StubAdapter("telegram", false), new StubAdapter("cli", false)));
    host.start();
    var runtime = runtime();
    var later =
        runtime.register(
            "telegram",
            ControlCancellationHandle.from(new TurnCancellationSource()),
            NOW.plusSeconds(2));
    var earlier =
        runtime.register(
            "telegram", ControlCancellationHandle.from(new TurnCancellationSource()), NOW);
    var service = service(host, runtime);
    var subscription =
        runtime.eventHub().subscribe(earlier.turnRef().orElseThrow(), "actor-safe-reference");
    earlier.observe(
        OutboundMessage.started(
            "raw-turn", "raw-session", new MessageRoute("telegram", "raw-route")));
    for (int sequence = 1; sequence <= 3; sequence++) {
      earlier.observe(
          OutboundMessage.delta(
              "raw-turn",
              "raw-session",
              new MessageRoute("telegram", "raw-route"),
              sequence,
              "message-body"));
    }

    ControlStatusResponse status = service.status();
    ControlTurnsResponse turns = service.turns();

    assertThat(status.schemaVersion()).isEqualTo(1);
    assertThat(status.observedAt()).isEqualTo(NOW);
    assertThat(status.host()).isEqualTo(new ControlStatusResponse.Host("RUNNING", ""));
    assertThat(status.control().state()).isEqualTo("READY");
    assertThat(status.control().activeTurns()).isEqualTo(2);
    assertThat(status.control().recentTerminalTombstones()).isZero();
    assertThat(status.control().subscriberBufferCapacity()).isEqualTo(64);
    assertThat(status.control().eventSubscribers()).isEqualTo(1);
    assertThat(status.control().maxSubscriberQueueDepth()).isEqualTo(4);
    assertThat(status.channels())
        .extracting(ControlStatusResponse.Channel::name)
        .containsExactly("cli", "telegram");
    assertThat(turns.items())
        .extracting(ControlTurnsResponse.Item::turnRef)
        .containsExactly(
            earlier.turnRef().orElseThrow().value(), later.turnRef().orElseThrow().value());
    assertThat(turns.items().getFirst().state()).isEqualTo("ACTIVE");
    assertThat(turns.items().getFirst().lastSequence()).isEqualTo(3L);
    assertThat(turns.toString()).doesNotContain("raw-session", "raw-route", "message-body");
    subscription.close();
  }

  @Test
  void isolatesOneChannelSnapshotFailureAndDegradesWithStableControlCode() {
    var host =
        new ChannelHost(
            List.of(new StubAdapter("healthy", false), new StubAdapter("broken", true)));
    host.start();

    ControlStatusResponse response = service(host, runtime()).status();

    assertThat(response.control().state()).isEqualTo("DEGRADED");
    assertThat(response.control().code()).isEqualTo("CONTROL_SNAPSHOT_UNAVAILABLE");
    assertThat(response.channels())
        .extracting(ControlStatusResponse.Channel::name)
        .containsExactly("broken", "healthy");
    assertThat(response.channels().getFirst().code()).isEqualTo("SNAPSHOT_FAILED");
    assertThat(response.channels().getLast().state()).isEqualTo("RUNNING");
  }

  @Test
  void keepsUnknownExecutionAsReliabilityCountWithoutCreatingAnActiveTurn() {
    var reliability =
        new ChannelReliabilityStatus(
            ChannelReliabilityStatus.Mode.SQLITE,
            ChannelReliabilityStatus.LedgerState.READY,
            0,
            1,
            0,
            "");
    var host = new ChannelHost(List.of(new StubAdapter("telegram", false, reliability)));
    host.start();
    var service = service(host, runtime());

    ControlStatusResponse status = service.status();

    assertThat(status.control().activeTurns()).isZero();
    assertThat(status.channels().getFirst().reliability().unknownExecutions()).isOne();
    assertThat(service.turns().items()).isEmpty();
  }

  @Test
  void projectsMinimalSortedReadOnlyIndexWithAnOpaqueContinuation() {
    assertThat(ControlPlaneStatusService.defaultIndexPageSize()).isEqualTo(20);
    var reliability =
        new ChannelReliabilityStatus(
            ChannelReliabilityStatus.Mode.SQLITE,
            ChannelReliabilityStatus.LedgerState.READY,
            0,
            1,
            0,
            "");
    var host =
        new ChannelHost(
            List.of(
                new StubAdapter("telegram", false, reliability), new StubAdapter("cli", false)));
    host.start();
    var runtime = runtime();
    var first =
        runtime.register("cli", ControlCancellationHandle.from(new TurnCancellationSource()), NOW);
    var second =
        runtime.register(
            "telegram",
            ControlCancellationHandle.from(new TurnCancellationSource()),
            NOW.minusSeconds(1));
    var terminal =
        runtime.register(
            "telegram",
            ControlCancellationHandle.from(new TurnCancellationSource()),
            NOW.minusSeconds(2));
    first.observe(
        OutboundMessage.started(
            "raw-turn-secret",
            "raw-session-secret",
            new MessageRoute("telegram", "raw-route-secret")));
    first.observe(
        OutboundMessage.delta(
            "raw-turn-secret",
            "raw-session-secret",
            new MessageRoute("telegram", "raw-route-secret"),
            1,
            "private message body"));
    terminal.closeWithoutTerminal();
    var service = service(host, runtime);

    ControlIndexResponse firstPage = service.index(1, "", "AAAAAAAAAAAAAAAAAAAAAA");
    ControlIndexResponse secondPage =
        service.index(50, firstPage.nextCursor(), "AAAAAAAAAAAAAAAAAAAAAA");

    assertThat(firstPage.schemaVersion()).isEqualTo(1);
    assertThat(firstPage.state()).isEqualTo("READY");
    assertThat(firstPage.code()).isEmpty();
    assertThat(firstPage.channels())
        .extracting(ControlIndexResponse.Channel::channel)
        .containsExactly("cli", "telegram");
    assertThat(firstPage.channels().getLast().unknownExecutionCount()).isEqualTo(1);
    assertThat(firstPage.turns())
        .containsExactly(
            new ControlIndexResponse.Turn(
                first.turnRef().orElseThrow().value(), "cli", "ACTIVE", 1L));
    assertThat(firstPage.nextCursor()).matches("[A-Za-z0-9_-]{22}");
    assertThat(secondPage.turns())
        .containsExactly(
            new ControlIndexResponse.Turn(
                second.turnRef().orElseThrow().value(), "telegram", "ACTIVE", null));
    assertThat(secondPage.nextCursor()).isEmpty();
    assertThat(firstPage.toString())
        .doesNotContain(
            "raw-turn-secret",
            "raw-session-secret",
            "raw-route-secret",
            "private message body",
            "AAAAAAAAAAAAAAAAAAAAAA");
  }

  @Test
  void convertsAnUnavailableSnapshotToAStableRedactedIndex() {
    var host = new ChannelHost(List.of(new StubAdapter("telegram", true)));
    host.start();

    ControlIndexResponse response =
        service(host, runtime()).index(20, "", "AAAAAAAAAAAAAAAAAAAAAA");

    assertThat(response.state()).isEqualTo("DEGRADED");
    assertThat(response.code()).isEqualTo("CONTROL_SNAPSHOT_UNAVAILABLE");
    assertThat(response.channels()).isEmpty();
    assertThat(response.turns()).isEmpty();
    assertThat(response.nextCursor()).isEmpty();
    assertThat(response.toString()).doesNotContain("snapshot-secret");
  }

  @Test
  void reportsRegistrySaturationWithoutCancellingOrRejectingTheRunningTurn() {
    ControlPlaneProperties properties = properties(1);
    var next = new java.util.concurrent.atomic.AtomicInteger();
    var runtime =
        new ControlPlaneRuntime(
            properties, Clock.fixed(NOW, ZoneOffset.UTC), () -> reference(next.incrementAndGet()));
    var source = new TurnCancellationSource();
    var first = runtime.register("telegram", ControlCancellationHandle.from(source), NOW);
    var rejected =
        runtime.register(
            "telegram", ControlCancellationHandle.from(new TurnCancellationSource()), NOW);
    var host = new ChannelHost(List.of());
    host.start();

    ControlStatusResponse status =
        new ControlPlaneStatusService(Clock.fixed(NOW, ZoneOffset.UTC), host, runtime, properties)
            .status();

    assertThat(first.registered()).isTrue();
    assertThat(rejected.registered()).isFalse();
    assertThat(source.token().isCancellationRequested()).isFalse();
    assertThat(status.control().state()).isEqualTo("DEGRADED");
    assertThat(status.control().code()).isEqualTo("CONTROL_TURN_REGISTRY_SATURATED");
  }

  private static ControlPlaneStatusService service(ChannelHost host, ControlPlaneRuntime runtime) {
    return new ControlPlaneStatusService(
        Clock.fixed(NOW, ZoneOffset.UTC), host, runtime, properties());
  }

  static ControlPlaneRuntime runtime() {
    var next = new java.util.concurrent.atomic.AtomicInteger();
    return new ControlPlaneRuntime(
        properties(), Clock.fixed(NOW, ZoneOffset.UTC), () -> reference(next.incrementAndGet()));
  }

  static ControlPlaneProperties properties() {
    return properties(128);
  }

  private static ControlPlaneProperties properties(int maxActiveTurns) {
    return new ControlPlaneProperties(
        "LOOPBACK",
        java.time.Duration.ofMinutes(15),
        4,
        maxActiveTurns,
        java.time.Duration.ofMinutes(5),
        1024,
        8,
        64,
        java.time.Duration.ofSeconds(15),
        java.time.Duration.ofMinutes(15),
        java.time.Duration.ofSeconds(2));
  }

  static ControlTurnRef reference(int value) {
    byte[] bytes = new byte[16];
    bytes[15] = (byte) value;
    return ControlTurnRef.fromBytes(bytes);
  }

  private static final class StubAdapter implements ChannelAdapter {
    private final String name;
    private final boolean failSnapshot;
    private final ChannelReliabilityStatus reliability;
    private ChannelState state = ChannelState.NEW;

    private StubAdapter(String name, boolean failSnapshot) {
      this(name, failSnapshot, ChannelReliabilityStatus.disabled());
    }

    private StubAdapter(String name, boolean failSnapshot, ChannelReliabilityStatus reliability) {
      this.name = name;
      this.failSnapshot = failSnapshot;
      this.reliability = reliability;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public void start() {
      state = ChannelState.RUNNING;
    }

    @Override
    public void stopAccepting() {
      state = ChannelState.STOPPING;
    }

    @Override
    public ChannelStatusSnapshot snapshot() {
      if (failSnapshot) {
        throw new IllegalStateException("snapshot-secret");
      }
      return new ChannelStatusSnapshot(name, state, "", 0, 0, reliability);
    }

    @Override
    public void close() {
      state = ChannelState.STOPPED;
    }
  }
}
