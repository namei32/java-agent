package io.namei.agent.application.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.TurnCancellationSource;
import io.namei.agent.kernel.control.ControlCancelResult;
import io.namei.agent.kernel.control.ControlTurnRef;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("failure")
class ActiveTurnRegistryConcurrencyTest {
  private static final Instant START = Instant.parse("2026-07-17T00:00:00Z");

  @Test
  void concurrentCancellationHasExactlyOneFirstWriter() throws Exception {
    TurnCancellationSource source = new TurnCancellationSource();
    ControlTurnRef reference = ActiveTurnRegistryTest.ref(1);
    ActiveTurnRegistry registry = registry(reference);
    registry.register("telegram", ControlCancellationHandle.from(source), START);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<ControlCancellationOutcome> first = new AtomicReference<>();
    AtomicReference<ControlCancellationOutcome> second = new AtomicReference<>();

    Thread firstThread = startCancel(registry, reference, ready, release, first);
    Thread secondThread = startCancel(registry, reference, ready, release, second);
    ready.await();
    release.countDown();
    firstThread.join();
    secondThread.join();

    assertThat(List.of(first.get().result(), second.get().result()))
        .containsExactlyInAnyOrder(
            ControlCancelResult.CANCELLATION_REQUESTED, ControlCancelResult.ALREADY_REQUESTED);
  }

  @Test
  void terminalCanWinWhileCancellationCallbackIsInFlight() throws Exception {
    ControlTurnRef reference = ActiveTurnRegistryTest.ref(1);
    ActiveTurnRegistry registry = registry(reference);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var handle = new BlockingCancellationHandle(entered, release);
    ActiveTurnRegistration registration = registry.register("telegram", handle, START);
    AtomicReference<ControlCancellationOutcome> outcome = new AtomicReference<>();

    Thread cancel = Thread.ofVirtual().start(() -> outcome.set(registry.cancel(reference)));
    entered.await();
    registration.observe(ActiveTurnRegistryTest.started());
    registration.observe(ActiveTurnRegistryTest.completed(1, "完成"));
    release.countDown();
    cancel.join();

    assertThat(outcome.get().result()).isEqualTo(ControlCancelResult.ALREADY_TERMINAL);
    assertThat(registry.snapshot().activeTurns()).isEmpty();
    assertThat(registry.cancel(reference).result()).isEqualTo(ControlCancelResult.ALREADY_TERMINAL);
  }

  private static Thread startCancel(
      ActiveTurnRegistry registry,
      ControlTurnRef reference,
      CountDownLatch ready,
      CountDownLatch release,
      AtomicReference<ControlCancellationOutcome> result) {
    return Thread.ofVirtual()
        .start(
            () -> {
              ready.countDown();
              await(release);
              result.set(registry.cancel(reference));
            });
  }

  private static ActiveTurnRegistry registry(ControlTurnRef reference) {
    return new ActiveTurnRegistry(
        new ActiveTurnRegistryTest.MutableClock(START),
        () -> reference,
        2,
        Duration.ofMinutes(5),
        2);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static final class BlockingCancellationHandle implements ControlCancellationHandle {
    private final CountDownLatch entered;
    private final CountDownLatch release;
    private volatile boolean requested;

    private BlockingCancellationHandle(CountDownLatch entered, CountDownLatch release) {
      this.entered = entered;
      this.release = release;
    }

    @Override
    public boolean requestCancellation() {
      entered.countDown();
      await(release);
      requested = true;
      return true;
    }

    @Override
    public boolean isCancellationRequested() {
      return requested;
    }

    @Override
    public io.namei.agent.kernel.channel.TurnCancellationCode reason() {
      return io.namei.agent.kernel.channel.TurnCancellationCode.REQUESTED;
    }
  }
}
