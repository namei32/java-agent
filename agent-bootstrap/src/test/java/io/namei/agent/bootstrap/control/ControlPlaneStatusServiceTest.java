package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.TurnCancellationSource;
import io.namei.agent.application.control.ControlCancellationHandle;
import io.namei.agent.bootstrap.channel.ChannelAdapter;
import io.namei.agent.bootstrap.channel.ChannelHost;
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
    assertThat(status.control().maxSubscriberQueueDepth()).isEqualTo(1);
    assertThat(status.channels())
        .extracting(ControlStatusResponse.Channel::name)
        .containsExactly("cli", "telegram");
    assertThat(turns.items())
        .extracting(ControlTurnsResponse.Item::turnRef)
        .containsExactly(
            earlier.turnRef().orElseThrow().value(), later.turnRef().orElseThrow().value());
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
    return new ControlPlaneProperties(
        "LOOPBACK",
        java.time.Duration.ofMinutes(15),
        4,
        128,
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
    private ChannelState state = ChannelState.NEW;

    private StubAdapter(String name, boolean failSnapshot) {
      this.name = name;
      this.failSnapshot = failSnapshot;
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
      return new ChannelStatusSnapshot(name, state, "", 0, 0);
    }

    @Override
    public void close() {
      state = ChannelState.STOPPED;
    }
  }
}
