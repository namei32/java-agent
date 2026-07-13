package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("failure")
class KeyedSessionExecutionGateTest {
  @Test
  void rejectsNegativeWaitTimeout() {
    assertThatThrownBy(() -> new KeyedSessionExecutionGate(Duration.ofNanos(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void zeroWaitTimeoutAttemptsImmediately() throws Exception {
    var gate = new KeyedSessionExecutionGate(Duration.ZERO);
    var firstEntered = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () ->
                  gate.execute(
                      "same",
                      () -> {
                        firstEntered.countDown();
                        await(releaseFirst);
                        return "first";
                      }));
      assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();

      try {
        assertThatThrownBy(() -> gate.execute("same", () -> "second"))
            .isInstanceOf(SessionLockTimeoutException.class);
      } finally {
        releaseFirst.countDown();
      }
      assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("first");
    }
    assertThat(gate.activeEntryCount()).isZero();
  }

  @Test
  void preservesPositiveNanosecondPrecision() throws Exception {
    var gate = new KeyedSessionExecutionGate(Duration.ofNanos(1));
    var timeoutField = KeyedSessionExecutionGate.class.getDeclaredField("waitTimeoutNanos");
    timeoutField.setAccessible(true);

    assertThat(timeoutField.getLong(gate)).isEqualTo(1L);
  }

  @Test
  void saturatesPositiveWaitTimeoutWhenNanosecondConversionOverflows() throws Exception {
    var gate = new KeyedSessionExecutionGate(Duration.ofSeconds(Long.MAX_VALUE));
    var timeoutField = KeyedSessionExecutionGate.class.getDeclaredField("waitTimeoutNanos");
    timeoutField.setAccessible(true);

    assertThat(timeoutField.getLong(gate)).isEqualTo(Long.MAX_VALUE);
    assertThat(gate.execute("same", () -> "done")).isEqualTo("done");
    assertThat(gate.activeEntryCount()).isZero();
  }

  @Test
  void propagatesActionExceptionAndReclaimsEntry() {
    var gate = new KeyedSessionExecutionGate(Duration.ofSeconds(1));
    var failure = new IllegalStateException("action failed");

    assertThatThrownBy(
            () ->
                gate.execute(
                    "same",
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);
    assertThat(gate.execute("same", () -> "next")).isEqualTo("next");
    assertThat(gate.activeEntryCount()).isZero();
  }

  @Test
  void interruptedWaiterReclaimsEntryAndPreservesInterruptStatus() throws Exception {
    var gate = new KeyedSessionExecutionGate(Duration.ofDays(1));
    var firstEntered = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var waiterStarted = new CountDownLatch(1);
    var waiterThread = new AtomicReference<Thread>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () ->
                  gate.execute(
                      "same",
                      () -> {
                        firstEntered.countDown();
                        await(releaseFirst);
                        return "first";
                      }));
      assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();

      var waiter =
          executor.submit(
              () -> {
                var currentThread = Thread.currentThread();
                waiterThread.set(currentThread);
                waiterStarted.countDown();
                try {
                  gate.execute("same", () -> "unexpected");
                  throw new AssertionError("等待线程不应获得执行许可");
                } catch (SessionLockTimeoutException exception) {
                  return new InterruptObservation(exception, currentThread.isInterrupted());
                }
              });
      assertThat(waiterStarted.await(1, TimeUnit.SECONDS)).isTrue();

      try {
        awaitWaiting(waiterThread);
        waiterThread.get().interrupt();
        var observation = waiter.get(1, TimeUnit.SECONDS);
        assertThat(observation.exception()).isInstanceOf(SessionLockTimeoutException.class);
        assertThat(observation.interrupted()).isTrue();
      } finally {
        var thread = waiterThread.get();
        if (thread != null && waiterStarted.getCount() == 0) {
          thread.interrupt();
        }
        releaseFirst.countDown();
      }
      assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("first");
    }

    assertThat(gate.activeEntryCount()).isZero();
    assertThat(gate.execute("same", () -> "next")).isEqualTo("next");
    assertThat(gate.activeEntryCount()).isZero();
  }

  @Test
  void serializesSameSessionAndReclaimsEntry() throws Exception {
    var gate = new KeyedSessionExecutionGate(Duration.ofSeconds(2));
    var firstEntered = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var secondThread = new AtomicReference<Thread>();
    var secondEntered = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () ->
                  gate.execute(
                      "same",
                      () -> {
                        firstEntered.countDown();
                        await(releaseFirst);
                        return "first";
                      }));
      assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();

      var second =
          executor.submit(
              () -> {
                secondThread.set(Thread.currentThread());
                return gate.execute(
                    "same",
                    () -> {
                      secondEntered.countDown();
                      return "second";
                    });
              });
      awaitWaiting(secondThread);
      assertThat(secondEntered.getCount()).isEqualTo(1);

      releaseFirst.countDown();
      assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("first");
      assertThat(second.get(1, TimeUnit.SECONDS)).isEqualTo("second");
    }
    assertThat(gate.activeEntryCount()).isZero();
  }

  @Test
  void allowsDifferentSessionsToOverlap() throws Exception {
    var gate = new KeyedSessionExecutionGate(Duration.ofSeconds(1));
    var bothEntered = new CountDownLatch(2);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var one = executor.submit(() -> gate.execute("one", () -> awaitBoth(bothEntered)));
      var two = executor.submit(() -> gate.execute("two", () -> awaitBoth(bothEntered)));

      assertThat(one.get(1, TimeUnit.SECONDS)).isEqualTo("done");
      assertThat(two.get(1, TimeUnit.SECONDS)).isEqualTo("done");
    }
  }

  @Test
  void timesOutWaitingForBusySession() throws Exception {
    var gate = new KeyedSessionExecutionGate(Duration.ofMillis(30));
    var firstEntered = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () ->
                  gate.execute(
                      "same",
                      () -> {
                        firstEntered.countDown();
                        await(releaseFirst);
                        return "first";
                      }));
      assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> gate.execute("same", () -> "second"))
          .isInstanceOf(SessionLockTimeoutException.class);

      releaseFirst.countDown();
      assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("first");
    }
    assertThat(gate.activeEntryCount()).isZero();
  }

  private static String awaitBoth(CountDownLatch latch) {
    latch.countDown();
    await(latch);
    return "done";
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) {
        throw new AssertionError("等待超时");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }

  private static void awaitWaiting(AtomicReference<Thread> threadReference) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (System.nanoTime() < deadline) {
      var thread = threadReference.get();
      if (thread == null) {
        Thread.onSpinWait();
        continue;
      }
      var state = thread.getState();
      if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
        return;
      }
      if (!thread.isAlive()) {
        throw new AssertionError("等待线程提前结束");
      }
      Thread.onSpinWait();
    }
    var thread = threadReference.get();
    throw new AssertionError("等待线程未进入等待状态: " + (thread == null ? "未启动" : thread.getState()));
  }

  private record InterruptObservation(SessionLockTimeoutException exception, boolean interrupted) {}
}
