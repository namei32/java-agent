package io.namei.agent.application.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.TurnCancellationSource;
import io.namei.agent.kernel.channel.TurnCancellationCode;
import io.namei.agent.kernel.control.ControlCancelResult;
import io.namei.agent.kernel.control.ControlTurnRef;
import io.namei.agent.kernel.control.ControlTurnState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActiveTurnCancellationTest {
  private static final Instant START = Instant.parse("2026-07-17T00:00:00Z");

  @Test
  void firstRequestedCancellationWinsAndRepeatIsIdempotent() {
    TurnCancellationSource source = new TurnCancellationSource();
    ActiveTurnRegistry registry = registry(ref(1));
    ControlTurnRef turnRef =
        registry
            .register("telegram", ControlCancellationHandle.from(source), START)
            .turnRef()
            .orElseThrow();

    ControlCancellationOutcome first = registry.cancel(turnRef);
    ControlCancellationOutcome repeated = registry.cancel(turnRef);

    assertThat(first.result()).isEqualTo(ControlCancelResult.CANCELLATION_REQUESTED);
    assertThat(first.state()).contains(ControlTurnState.CANCELLATION_REQUESTED);
    assertThat(repeated.result()).isEqualTo(ControlCancelResult.ALREADY_REQUESTED);
    assertThat(source.token().reason()).isEqualTo(TurnCancellationCode.REQUESTED);
  }

  @Test
  void neverOverwritesExistingChannelCancellationReasons() {
    for (TurnCancellationCode reason :
        List.of(
            TurnCancellationCode.CHANNEL_DISCONNECTED,
            TurnCancellationCode.BACKPRESSURE_EXCEEDED,
            TurnCancellationCode.SHUTDOWN)) {
      TurnCancellationSource source = new TurnCancellationSource();
      source.cancel(reason);
      ActiveTurnRegistry registry = registry(ref(reason.ordinal() + 1));
      ControlTurnRef turnRef =
          registry
              .register("telegram", ControlCancellationHandle.from(source), START)
              .turnRef()
              .orElseThrow();

      assertThat(registry.cancel(turnRef).result())
          .isEqualTo(ControlCancelResult.ALREADY_CANCELLED);
      assertThat(source.token().reason()).isEqualTo(reason);
    }
  }

  @Test
  void cancellationTargetsOnlyOneTurn() {
    TurnCancellationSource first = new TurnCancellationSource();
    TurnCancellationSource second = new TurnCancellationSource();
    ActiveTurnRegistry registry = registry(ref(1), ref(2));
    ControlTurnRef firstRef =
        registry
            .register("telegram", ControlCancellationHandle.from(first), START)
            .turnRef()
            .orElseThrow();
    registry.register("telegram", ControlCancellationHandle.from(second), START.plusSeconds(1));

    registry.cancel(firstRef);

    assertThat(first.token().isCancellationRequested()).isTrue();
    assertThat(second.token().isCancellationRequested()).isFalse();
  }

  private static ActiveTurnRegistry registry(ControlTurnRef first, ControlTurnRef... rest) {
    ArrayDeque<ControlTurnRef> references = new ArrayDeque<>();
    references.add(first);
    references.addAll(List.of(rest));
    return new ActiveTurnRegistry(
        new ActiveTurnRegistryTest.MutableClock(START),
        references::removeFirst,
        8,
        Duration.ofMinutes(5),
        8);
  }

  private static ControlTurnRef ref(int lastByte) {
    return ActiveTurnRegistryTest.ref(lastByte);
  }
}
