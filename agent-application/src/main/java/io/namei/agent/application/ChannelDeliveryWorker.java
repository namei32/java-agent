package io.namei.agent.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class ChannelDeliveryWorker implements ChannelDeliveryWakeSignal, AutoCloseable {
  private static final String WORKER_NAME = "channel-delivery-worker";

  private final ChannelDeliveryProcessor processor;
  private final ReliableTurnStarter starter;
  private final Clock clock;
  private final ChannelDeliveryWorkerSettings settings;
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean shutdown = new AtomicBoolean();
  private final AtomicLong signalVersion = new AtomicLong();
  private final ReentrantLock waitLock = new ReentrantLock();
  private final Condition signalled = waitLock.newCondition();
  private volatile Thread thread;

  public ChannelDeliveryWorker(
      ChannelDeliveryProcessor processor,
      ReliableTurnStarter starter,
      Clock clock,
      ChannelDeliveryWorkerSettings settings) {
    this.processor = Objects.requireNonNull(processor, "processor");
    this.starter = Objects.requireNonNull(starter, "starter");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  public void start() {
    if (shutdown.get()) {
      throw new OutboundDeliveryException(OutboundDeliveryException.Reason.SHUTDOWN);
    }
    if (!started.compareAndSet(false, true)) {
      throw new IllegalStateException("Delivery Worker 已启动");
    }
    try {
      thread = Objects.requireNonNull(starter.start(WORKER_NAME, this::run), "starter 返回了 null");
    } catch (RuntimeException | Error failure) {
      started.set(false);
      throw failure;
    }
  }

  @Override
  public void signal() {
    signalVersion.incrementAndGet();
    waitLock.lock();
    try {
      signalled.signalAll();
    } finally {
      waitLock.unlock();
    }
  }

  private void run() {
    Instant knownRetry = null;
    while (!shutdown.get() && !Thread.currentThread().isInterrupted()) {
      if (knownRetry != null && !knownRetry.isAfter(clock.instant())) {
        knownRetry = null;
      }
      long observedSignal = signalVersion.get();
      Instant earliestRetry = knownRetry;
      int processed = 0;
      boolean empty = false;
      while (!shutdown.get()
          && !Thread.currentThread().isInterrupted()
          && processed < settings.batchSize()) {
        ChannelDeliveryStep step;
        try {
          step = Objects.requireNonNull(processor.processNext(), "processor 返回了 null");
        } catch (RuntimeException failure) {
          empty = true;
          break;
        }
        if (step.status() == ChannelDeliveryStep.Status.EMPTY) {
          empty = true;
          break;
        }
        processed++;
        if (step.status() == ChannelDeliveryStep.Status.RETRY_SCHEDULED
            && (earliestRetry == null || step.retryAt().isBefore(earliestRetry))) {
          earliestRetry = step.retryAt();
        }
      }
      knownRetry = earliestRetry;
      if (shutdown.get() || Thread.currentThread().isInterrupted()) {
        break;
      }
      if (!empty && processed == settings.batchSize()) {
        continue;
      }
      Duration wait = waitDuration(earliestRetry);
      if (wait.isZero()) {
        continue;
      }
      if (!awaitSignal(observedSignal, wait)) {
        break;
      }
    }
  }

  private Duration waitDuration(Instant retryAt) {
    if (retryAt == null) {
      return settings.idleWait();
    }
    Duration untilRetry = Duration.between(clock.instant(), retryAt);
    if (untilRetry.isZero() || untilRetry.isNegative()) {
      return Duration.ZERO;
    }
    return untilRetry.compareTo(settings.idleWait()) < 0 ? untilRetry : settings.idleWait();
  }

  private boolean awaitSignal(long observedSignal, Duration timeout) {
    try {
      waitLock.lockInterruptibly();
      try {
        if (shutdown.get() || signalVersion.get() != observedSignal) {
          return true;
        }
        signalled.awaitNanos(timeout.toNanos());
        return true;
      } finally {
        waitLock.unlock();
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public void close() {
    shutdown.set(true);
    signal();
    Thread running = thread;
    if (running == null || running == Thread.currentThread()) {
      return;
    }
    running.interrupt();
    try {
      running.join(Math.max(1, settings.shutdownTimeout().toMillis()));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
    }
  }

  public boolean isRunning() {
    Thread running = thread;
    return running != null && running.isAlive();
  }
}
