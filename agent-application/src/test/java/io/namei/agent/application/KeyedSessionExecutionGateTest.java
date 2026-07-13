package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class KeyedSessionExecutionGateTest {
  @Test
  void serializesSameSessionAndReclaimsEntry() throws Exception {
    var gate = new KeyedSessionExecutionGate(Duration.ofSeconds(2));
    var firstEntered = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var secondStarted = new CountDownLatch(1);
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
                secondStarted.countDown();
                return gate.execute(
                    "same",
                    () -> {
                      secondEntered.countDown();
                      return "second";
                    });
              });
      assertThat(secondStarted.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(secondEntered.await(100, TimeUnit.MILLISECONDS)).isFalse();

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
}
