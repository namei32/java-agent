package io.namei.agent.bootstrap.telegram;

import io.namei.agent.application.BoundedOutboundBuffer;
import io.namei.agent.application.control.ActiveTurnRegistration;
import io.namei.agent.kernel.channel.InboundMessage;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class ActiveTelegramTurn {
  private enum StartupState {
    PENDING,
    STARTED,
    FAILED,
    ABORTED
  }

  private final long chatId;
  private final InboundMessage inbound;
  private final BoundedOutboundBuffer buffer;
  private final CountDownLatch startup = new CountDownLatch(1);
  private final CountDownLatch producerDone = new CountDownLatch(1);
  private final AtomicReference<StartupState> startupState =
      new AtomicReference<>(StartupState.PENDING);
  private final AtomicBoolean cleaned = new AtomicBoolean();
  private volatile ActiveTurnRegistration controlRegistration = ActiveTurnRegistration.disabled();
  private volatile Thread worker;
  private volatile Thread producer;

  ActiveTelegramTurn(long chatId, InboundMessage inbound, BoundedOutboundBuffer buffer) {
    if (chatId <= 0) {
      throw new IllegalArgumentException("Telegram chatId 必须为正数");
    }
    this.chatId = chatId;
    this.inbound = Objects.requireNonNull(inbound, "inbound");
    this.buffer = Objects.requireNonNull(buffer, "buffer");
  }

  long chatId() {
    return chatId;
  }

  InboundMessage inbound() {
    return inbound;
  }

  BoundedOutboundBuffer buffer() {
    return buffer;
  }

  ActiveTurnRegistration controlRegistration() {
    return controlRegistration;
  }

  void controlRegistration(ActiveTurnRegistration registration) {
    controlRegistration = Objects.requireNonNull(registration, "registration");
  }

  void worker(Thread thread) {
    worker = Objects.requireNonNull(thread, "thread");
  }

  void producer(Thread thread) {
    producer = Objects.requireNonNull(thread, "thread");
  }

  boolean startupSucceeded() {
    boolean started = startupState.compareAndSet(StartupState.PENDING, StartupState.STARTED);
    startup.countDown();
    return started;
  }

  void startupFailed() {
    startupState.compareAndSet(StartupState.PENDING, StartupState.FAILED);
    startup.countDown();
    buffer.shutdown();
  }

  boolean awaitStartup(Duration timeout) {
    try {
      if (!startup.await(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS)) {
        abortStartup();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      abortStartup();
    }
    return startupState.get() == StartupState.STARTED;
  }

  void abortStartup() {
    if (startupState.compareAndSet(StartupState.PENDING, StartupState.ABORTED)) {
      startup.countDown();
    }
    buffer.shutdown();
    interruptWorkers();
  }

  void producerFinished() {
    producerDone.countDown();
  }

  boolean producerIsDone() {
    return producerDone.getCount() == 0;
  }

  boolean awaitProducer(Duration timeout) {
    try {
      return producerDone.await(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  boolean markCleaned() {
    return cleaned.compareAndSet(false, true);
  }

  void interruptWorkers() {
    Thread current = Thread.currentThread();
    Thread producerThread = producer;
    if (producerThread != null && producerThread != current) {
      producerThread.interrupt();
    }
    Thread workerThread = worker;
    if (workerThread != null && workerThread != current) {
      workerThread.interrupt();
    }
  }

  boolean joinWorkersUntil(long deadlineNanos) throws InterruptedException {
    return joinUntil(producer, deadlineNanos) && joinUntil(worker, deadlineNanos);
  }

  boolean workersStopped() {
    Thread producerThread = producer;
    Thread workerThread = worker;
    return (producerThread == null || !producerThread.isAlive())
        && (workerThread == null || !workerThread.isAlive());
  }

  private static boolean joinUntil(Thread thread, long deadlineNanos) throws InterruptedException {
    if (thread == null || thread == Thread.currentThread() || !thread.isAlive()) {
      return true;
    }
    long remaining = deadlineNanos - System.nanoTime();
    return remaining > 0 && thread.join(Duration.ofNanos(remaining));
  }

  @Override
  public String toString() {
    return "ActiveTelegramTurn[sensitiveFields=<redacted>]";
  }
}
