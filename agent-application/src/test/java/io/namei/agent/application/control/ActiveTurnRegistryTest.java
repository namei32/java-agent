package io.namei.agent.application.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.application.TurnCancellationSource;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.control.ControlCancelResult;
import io.namei.agent.kernel.control.ControlTerminalKind;
import io.namei.agent.kernel.control.ControlTurnRef;
import io.namei.agent.kernel.control.ControlTurnState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActiveTurnRegistryTest {
  private static final Instant START = Instant.parse("2026-07-17T00:00:00Z");
  private static final MessageRoute ROUTE = new MessageRoute("telegram", "conversation-secret");

  @Test
  void tracksSafeSequenceAndCreatesBoundedTerminalTombstone() {
    MutableClock clock = new MutableClock(START);
    ActiveTurnRegistry registry = registry(clock, 2, 2, ref(1), ref(2));
    TurnCancellationSource cancellation = new TurnCancellationSource();
    ActiveTurnRegistration registration =
        registry.register("telegram", ControlCancellationHandle.from(cancellation), START);

    assertThat(registration.registered()).isTrue();
    ControlTurnRef turnRef = registration.turnRef().orElseThrow();
    assertThat(registry.snapshot().activeTurns())
        .containsExactly(
            new ActiveTurnSnapshot(turnRef, "telegram", ControlTurnState.ACTIVE, START, null, 0));

    registration.observe(started());
    registration.observe(delta(1, "片段"));
    registration.observe(completed(2, "完整回答"));
    registration.closeWithoutTerminal();

    assertThat(registry.snapshot().activeTurns()).isEmpty();
    assertThat(registry.snapshot().terminalTombstones()).isEqualTo(1);
    assertThat(registry.cancel(turnRef).result()).isEqualTo(ControlCancelResult.ALREADY_TERMINAL);
    assertThat(registry.terminalKind(turnRef)).contains(ControlTerminalKind.COMPLETED);

    clock.advance(Duration.ofMinutes(5).plusNanos(1));

    assertThat(registry.cancel(turnRef).result()).isEqualTo(ControlCancelResult.NOT_FOUND);
    assertThat(registry.snapshot().terminalTombstones()).isZero();
  }

  @Test
  void registrySaturationReturnsNoOpWithoutStoppingExistingTurn() {
    MutableClock clock = new MutableClock(START);
    ActiveTurnRegistry registry = registry(clock, 1, 2, ref(1), ref(2));
    TurnCancellationSource first = new TurnCancellationSource();
    TurnCancellationSource second = new TurnCancellationSource();

    ActiveTurnRegistration accepted =
        registry.register("telegram", ControlCancellationHandle.from(first), START);
    ActiveTurnRegistration rejected =
        registry.register("telegram", ControlCancellationHandle.from(second), START.plusSeconds(1));

    assertThat(accepted.registered()).isTrue();
    assertThat(rejected.registered()).isFalse();
    rejected.observe(started());
    rejected.closeWithoutTerminal();
    assertThat(registry.snapshot().saturated()).isTrue();
    assertThat(registry.snapshot().activeTurns()).hasSize(1);
    assertThat(first.token().isCancellationRequested()).isFalse();
    assertThat(second.token().isCancellationRequested()).isFalse();
  }

  @Test
  void activeSnapshotsUseStartTimeThenReferenceOrderAndRedactSensitiveValues() {
    MutableClock clock = new MutableClock(START);
    ControlTurnRef laterRef = ref(2);
    ControlTurnRef earlierRef = ref(1);
    ActiveTurnRegistry registry = registry(clock, 3, 3, laterRef, earlierRef);

    registry.register(
        "telegram",
        ControlCancellationHandle.from(new TurnCancellationSource()),
        START.plusSeconds(1));
    registry.register(
        "telegram", ControlCancellationHandle.from(new TurnCancellationSource()), START);

    List<ActiveTurnSnapshot> snapshots = registry.snapshot().activeTurns();
    assertThat(snapshots)
        .extracting(ActiveTurnSnapshot::turnRef)
        .containsExactly(earlierRef, laterRef);
    assertThat(snapshots.toString())
        .doesNotContain(
            earlierRef.value(), laterRef.value(), "conversation-secret", "session-secret");
  }

  @Test
  void closeWithoutTerminalIsIdempotentAndUsesSourceEndedTombstone() {
    MutableClock clock = new MutableClock(START);
    ActiveTurnRegistry registry = registry(clock, 1, 1, ref(1));
    ActiveTurnRegistration registration =
        registry.register(
            "telegram", ControlCancellationHandle.from(new TurnCancellationSource()), START);
    ControlTurnRef reference = registration.turnRef().orElseThrow();

    registration.closeWithoutTerminal();
    registration.closeWithoutTerminal();

    assertThat(registry.terminalKind(reference)).contains(ControlTerminalKind.SOURCE_ENDED);
    assertThat(registry.snapshot().terminalTombstones()).isEqualTo(1);
  }

  @Test
  void rejectsASequenceGapWithoutAdvancingTheSafeSnapshot() {
    MutableClock clock = new MutableClock(START);
    ActiveTurnRegistry registry = registry(clock, 1, 1, ref(1));
    ActiveTurnRegistration registration =
        registry.register(
            "telegram", ControlCancellationHandle.from(new TurnCancellationSource()), START);

    registration.observe(started());

    assertThatThrownBy(() -> registration.observe(delta(2, "越过序号")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("控制面观察 Message sequence 不连续");
    assertThat(registry.snapshot().activeTurns())
        .extracting(ActiveTurnSnapshot::lastSequence)
        .containsExactly(0L);
  }

  @Test
  void evictsTheEarliestExpiringTombstoneWhenCapacityIsReached() {
    MutableClock clock = new MutableClock(START);
    ControlTurnRef firstRef = ref(1);
    ControlTurnRef secondRef = ref(2);
    ActiveTurnRegistry registry = registry(clock, 1, 1, firstRef, secondRef);
    ActiveTurnRegistration first =
        registry.register(
            "telegram", ControlCancellationHandle.from(new TurnCancellationSource()), START);
    first.observe(started());
    first.observe(completed(1, "第一条"));
    clock.advance(Duration.ofSeconds(1));
    ActiveTurnRegistration second =
        registry.register(
            "telegram",
            ControlCancellationHandle.from(new TurnCancellationSource()),
            START.plusSeconds(1));
    second.observe(started());
    second.observe(completed(1, "第二条"));

    assertThat(registry.terminalKind(firstRef)).isEmpty();
    assertThat(registry.terminalKind(secondRef)).contains(ControlTerminalKind.COMPLETED);
    assertThat(registry.snapshot().terminalTombstones()).isEqualTo(1);
  }

  private static ActiveTurnRegistry registry(
      Clock clock, int maxActive, int maxTombstones, ControlTurnRef first, ControlTurnRef... rest) {
    ArrayDeque<ControlTurnRef> references = new ArrayDeque<>();
    references.add(first);
    references.addAll(List.of(rest));
    return new ActiveTurnRegistry(
        clock, () -> references.removeFirst(), maxActive, Duration.ofMinutes(5), maxTombstones);
  }

  static ControlTurnRef ref(int lastByte) {
    byte[] bytes = new byte[16];
    bytes[15] = (byte) lastByte;
    return ControlTurnRef.fromBytes(bytes);
  }

  static OutboundMessage started() {
    return OutboundMessage.started("turn-secret", "session-secret", ROUTE);
  }

  static OutboundMessage delta(long sequence, String content) {
    return OutboundMessage.delta("turn-secret", "session-secret", ROUTE, sequence, content);
  }

  static OutboundMessage completed(long sequence, String content) {
    return OutboundMessage.completed("turn-secret", "session-secret", ROUTE, sequence, content);
  }

  static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
