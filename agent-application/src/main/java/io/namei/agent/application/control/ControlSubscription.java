package io.namei.agent.application.control;

import io.namei.agent.kernel.control.ControlEventProjection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class ControlSubscription implements AutoCloseable {
  public static final Duration MAX_POLL_TIMEOUT = Duration.ofSeconds(60);

  private final ControlEventHub owner;
  private final ControlStreamOpening opening;
  private final String actorRef;
  private final Clock clock;
  private final Instant expiresAt;
  private final int capacity;
  private final ArrayDeque<ControlSequencedEvent> events;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition changed = lock.newCondition();
  private long nextDeliverySequence = 1;
  private ControlSubscriptionCloseReason closeReason;

  ControlSubscription(
      ControlEventHub owner,
      ControlStreamOpening opening,
      String actorRef,
      Clock clock,
      Instant expiresAt,
      int capacity) {
    this.owner = Objects.requireNonNull(owner, "owner");
    this.opening = Objects.requireNonNull(opening, "opening");
    this.actorRef = Objects.requireNonNull(actorRef, "actorRef");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    if (capacity < 1) {
      throw new IllegalArgumentException("控制面订阅 Buffer 容量必须为正数");
    }
    this.capacity = capacity;
    this.events = new ArrayDeque<>(capacity);
  }

  public ControlStreamOpening opening() {
    return opening;
  }

  public Optional<ControlSequencedEvent> poll(Duration timeout) {
    requirePollTimeout(timeout);
    if (!clock.instant().isBefore(expiresAt)) {
      owner.closeSubscription(this, ControlSubscriptionCloseReason.LIFETIME_EXCEEDED, false);
      return Optional.empty();
    }

    boolean expired = false;
    Optional<ControlSequencedEvent> result;
    try {
      lock.lockInterruptibly();
      try {
        long remaining = boundedWaitNanos(timeout, clock.instant());
        while (events.isEmpty() && closeReason == null && remaining > 0) {
          remaining = changed.awaitNanos(remaining);
          if (!clock.instant().isBefore(expiresAt)) {
            expired = true;
            break;
          }
        }
        result = events.isEmpty() ? Optional.empty() : Optional.of(events.removeFirst());
      } finally {
        lock.unlock();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      close();
      throw new IllegalStateException("控制面订阅轮询被中断");
    }
    if (expired) {
      owner.closeSubscription(this, ControlSubscriptionCloseReason.LIFETIME_EXCEEDED, false);
      return Optional.empty();
    }
    return result;
  }

  public boolean writeNext(ControlEventWriter writer, Duration timeout) {
    Objects.requireNonNull(writer, "writer");
    Optional<ControlSequencedEvent> event = poll(timeout);
    event.ifPresent(writer::write);
    return event.isPresent();
  }

  public Optional<ControlSubscriptionCloseReason> closeReason() {
    lock.lock();
    try {
      return Optional.ofNullable(closeReason);
    } finally {
      lock.unlock();
    }
  }

  public boolean isClosed() {
    return closeReason().isPresent();
  }

  @Override
  public void close() {
    owner.closeSubscription(this, ControlSubscriptionCloseReason.CLIENT_DISCONNECTED, false);
  }

  OfferResult offer(ControlEventProjection projection) {
    lock.lock();
    try {
      if (closeReason != null) {
        return OfferResult.CLOSED;
      }
      if (events.size() >= capacity || nextDeliverySequence == Long.MAX_VALUE) {
        return OfferResult.FULL;
      }
      events.addLast(new ControlSequencedEvent(nextDeliverySequence++, projection));
      changed.signal();
      return OfferResult.ACCEPTED;
    } finally {
      lock.unlock();
    }
  }

  boolean closeFromOwner(ControlSubscriptionCloseReason reason, boolean retainQueued) {
    lock.lock();
    try {
      if (closeReason != null) {
        return false;
      }
      closeReason = Objects.requireNonNull(reason, "reason");
      if (!retainQueued) {
        events.clear();
      }
      changed.signalAll();
      return true;
    } finally {
      lock.unlock();
    }
  }

  String actorRef() {
    return actorRef;
  }

  int queueDepth() {
    lock.lock();
    try {
      return events.size();
    } finally {
      lock.unlock();
    }
  }

  private long boundedWaitNanos(Duration timeout, Instant now) {
    long requested = timeout.toNanos();
    long lifetimeRemaining = Duration.between(now, expiresAt).toNanos();
    return Math.min(requested, Math.max(0, lifetimeRemaining));
  }

  private static void requirePollTimeout(Duration timeout) {
    if (timeout == null || timeout.isNegative() || timeout.compareTo(MAX_POLL_TIMEOUT) > 0) {
      throw new IllegalArgumentException("控制面订阅 Poll Timeout 无效");
    }
  }

  enum OfferResult {
    ACCEPTED,
    FULL,
    CLOSED
  }

  @Override
  public String toString() {
    return "ControlSubscription[turnRef=<redacted>, actorRef=<redacted>, closed="
        + isClosed()
        + "]";
  }
}
