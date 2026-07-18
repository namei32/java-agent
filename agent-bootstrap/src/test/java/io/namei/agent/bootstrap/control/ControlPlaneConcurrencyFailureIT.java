package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.TurnCancellationSource;
import io.namei.agent.application.control.ControlCancellationHandle;
import io.namei.agent.kernel.channel.TurnCancellationCode;
import io.namei.agent.kernel.control.ControlCancelResult;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("failure")
class ControlPlaneConcurrencyFailureIT {
  @Test
  void twoDashboardRequestsHaveExactlyOneFirstWriter() throws Exception {
    var fixture = fixture();
    var first = new AtomicReference<ControlCancelResult>();
    var second = new AtomicReference<ControlCancelResult>();
    race(
        () -> first.set(fixture.runtime.registry().cancel(fixture.reference).result()),
        () -> second.set(fixture.runtime.registry().cancel(fixture.reference).result()));

    assertThat(List.of(first.get(), second.get()))
        .containsExactlyInAnyOrder(
            ControlCancelResult.CANCELLATION_REQUESTED, ControlCancelResult.ALREADY_REQUESTED);
    assertThat(fixture.source.token().reason()).isEqualTo(TurnCancellationCode.REQUESTED);
  }

  @ParameterizedTest
  @MethodSource("channelReasons")
  void dashboardRaceNeverOverwritesAChannelFirstWriter(TurnCancellationCode channelReason)
      throws Exception {
    var fixture = fixture();
    var dashboard = new AtomicReference<ControlCancelResult>();
    race(
        () -> dashboard.set(fixture.runtime.registry().cancel(fixture.reference).result()),
        () -> fixture.source.cancel(channelReason));

    assertThat(fixture.source.token().reason()).isIn(TurnCancellationCode.REQUESTED, channelReason);
    if (fixture.source.token().reason() == channelReason) {
      assertThat(dashboard.get()).isEqualTo(ControlCancelResult.ALREADY_CANCELLED);
    } else {
      assertThat(dashboard.get())
          .isIn(ControlCancelResult.CANCELLATION_REQUESTED, ControlCancelResult.ALREADY_REQUESTED);
    }
  }

  private static Stream<TurnCancellationCode> channelReasons() {
    return Stream.of(
        TurnCancellationCode.CHANNEL_DISCONNECTED,
        TurnCancellationCode.BACKPRESSURE_EXCEEDED,
        TurnCancellationCode.SHUTDOWN);
  }

  private static Fixture fixture() {
    var runtime = ControlPlaneStatusServiceTest.runtime();
    var source = new TurnCancellationSource();
    var registration =
        runtime.register(
            "telegram",
            ControlCancellationHandle.from(source),
            Instant.parse("2026-07-18T00:00:00Z"));
    return new Fixture(runtime, source, registration.turnRef().orElseThrow());
  }

  private static void race(Runnable firstAction, Runnable secondAction) throws Exception {
    var ready = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    Thread first = start(ready, release, firstAction);
    Thread second = start(ready, release, secondAction);
    ready.await();
    release.countDown();
    first.join();
    second.join();
  }

  private static Thread start(CountDownLatch ready, CountDownLatch release, Runnable action) {
    return Thread.ofVirtual()
        .start(
            () -> {
              ready.countDown();
              await(release);
              action.run();
            });
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private record Fixture(
      ControlPlaneRuntime runtime,
      TurnCancellationSource source,
      io.namei.agent.kernel.control.ControlTurnRef reference) {}
}
