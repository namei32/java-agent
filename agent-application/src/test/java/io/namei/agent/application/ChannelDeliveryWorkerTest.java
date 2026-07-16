package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChannelDeliveryWorkerTest {
  private static final Instant NOW = Instant.parse("2026-07-16T09:00:00Z");
  private static final ChannelDeliveryWorkerSettings SETTINGS =
      new ChannelDeliveryWorkerSettings(8, Duration.ofHours(1), Duration.ofSeconds(2));

  @Test
  void scansDurableWorkOnStartupEvenWhenNoWakeWasObserved() throws Exception {
    var delivered = new CountDownLatch(1);
    var processor = new ScriptedProcessor(delivered);
    processor.add(ChannelDeliveryStep.delivered());
    processor.add(ChannelDeliveryStep.empty());
    var worker = worker(processor);

    worker.start();

    assertThat(delivered.await(2, TimeUnit.SECONDS)).isTrue();
    worker.close();
    assertThat(worker.isRunning()).isFalse();
  }

  @Test
  void failedAndUnknownDeliveriesDoNotBlockLaterWork() throws Exception {
    var delivered = new CountDownLatch(1);
    var processor = new ScriptedProcessor(delivered);
    processor.add(ChannelDeliveryStep.failed());
    processor.add(ChannelDeliveryStep.unknown());
    processor.add(ChannelDeliveryStep.delivered());
    processor.add(ChannelDeliveryStep.empty());
    var worker = worker(processor);

    worker.start();

    assertThat(delivered.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(processor.calls).hasValue(4);
    worker.close();
  }

  @Test
  void signalCannotBeLostBetweenEmptyScanAndConditionWait() throws Exception {
    var firstEmpty = new CountDownLatch(1);
    var releaseEmpty = new CountDownLatch(1);
    var delivered = new CountDownLatch(1);
    var processor = new ScriptedProcessor(delivered);
    processor.beforeFirstEmpty = firstEmpty;
    processor.releaseFirstEmpty = releaseEmpty;
    processor.add(ChannelDeliveryStep.empty());
    var worker = worker(processor);
    worker.start();
    assertThat(firstEmpty.await(2, TimeUnit.SECONDS)).isTrue();

    processor.add(ChannelDeliveryStep.delivered());
    processor.add(ChannelDeliveryStep.empty());
    worker.signal();
    releaseEmpty.countDown();

    assertThat(delivered.await(2, TimeUnit.SECONDS)).isTrue();
    worker.close();
  }

  @Test
  void closeInterruptsWaitAndJoinsWorkerWithinBound() throws Exception {
    var entered = new CountDownLatch(1);
    var interrupted = new AtomicBoolean();
    ChannelDeliveryProcessor blocking =
        () -> {
          entered.countDown();
          try {
            new CountDownLatch(1).await();
          } catch (InterruptedException failure) {
            interrupted.set(true);
            Thread.currentThread().interrupt();
          }
          return ChannelDeliveryStep.empty();
        };
    var worker = worker(blocking);
    worker.start();
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

    worker.close();

    assertThat(interrupted).isTrue();
    assertThat(worker.isRunning()).isFalse();
  }

  private static ChannelDeliveryWorker worker(ChannelDeliveryProcessor processor) {
    return new ChannelDeliveryWorker(
        processor,
        (name, task) -> {
          Thread thread = Thread.ofPlatform().name(name).unstarted(task);
          thread.start();
          return thread;
        },
        Clock.fixed(NOW, ZoneOffset.UTC),
        SETTINGS);
  }

  private static final class ScriptedProcessor implements ChannelDeliveryProcessor {
    private final ArrayDeque<ChannelDeliveryStep> steps = new ArrayDeque<>();
    private final CountDownLatch delivered;
    private final AtomicInteger calls = new AtomicInteger();
    private CountDownLatch beforeFirstEmpty;
    private CountDownLatch releaseFirstEmpty;
    private boolean firstEmpty = true;

    private ScriptedProcessor(CountDownLatch delivered) {
      this.delivered = delivered;
    }

    private synchronized void add(ChannelDeliveryStep step) {
      steps.addLast(step);
    }

    @Override
    public ChannelDeliveryStep processNext() {
      ChannelDeliveryStep step;
      synchronized (this) {
        calls.incrementAndGet();
        step = steps.isEmpty() ? ChannelDeliveryStep.empty() : steps.removeFirst();
      }
      if (step.status() == ChannelDeliveryStep.Status.EMPTY && firstEmpty) {
        firstEmpty = false;
        if (beforeFirstEmpty != null) {
          beforeFirstEmpty.countDown();
        }
        if (releaseFirstEmpty != null) {
          await(releaseFirstEmpty);
        }
      }
      if (step.status() == ChannelDeliveryStep.Status.DELIVERED) {
        delivered.countDown();
      }
      return step;
    }

    private static void await(CountDownLatch latch) {
      try {
        latch.await();
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new AssertionError(failure);
      }
    }
  }
}
