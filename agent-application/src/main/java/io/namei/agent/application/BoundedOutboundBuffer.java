package io.namei.agent.application;

import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.channel.OutboundSequenceValidator;
import io.namei.agent.kernel.channel.TurnCancellationCode;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class BoundedOutboundBuffer implements OutboundMessageSink {
  public static final int MAX_CAPACITY = 1024;
  public static final Duration MAX_WAIT = Duration.ofSeconds(30);

  private final int capacity;
  private final long publishTimeoutNanos;
  private final ArrayDeque<OutboundMessage> messages;
  private final OutboundSequenceValidator validator;
  private final TurnCancellationSource cancellation = new TurnCancellationSource();
  private final ReentrantLock lock = new ReentrantLock(true);
  private final Condition notEmpty = lock.newCondition();
  private final Condition notFull = lock.newCondition();
  private State state = State.OPEN;

  public BoundedOutboundBuffer(InboundMessage inbound, int capacity, Duration publishTimeout) {
    if (inbound == null) {
      throw new IllegalArgumentException("inbound 不能为空");
    }
    if (capacity < 1 || capacity > MAX_CAPACITY) {
      throw new IllegalArgumentException("出站缓冲容量必须在 1.." + MAX_CAPACITY + " 之间");
    }
    requirePositiveBounded(publishTimeout, "publishTimeout");
    this.capacity = capacity;
    this.publishTimeoutNanos = publishTimeout.toNanos();
    this.messages = new ArrayDeque<>(capacity);
    this.validator = new OutboundSequenceValidator(inbound);
  }

  @Override
  public void publish(OutboundMessage message) {
    try {
      lock.lockInterruptibly();
      try {
        ensureOpen();
        validate(message);
        long remaining = publishTimeoutNanos;
        while (messages.size() >= capacity) {
          ensureOpen();
          if (remaining <= 0) {
            transition(State.BACKPRESSURED, TurnCancellationCode.BACKPRESSURE_EXCEEDED, false);
            throw delivery(OutboundDeliveryException.Reason.BACKPRESSURE_EXCEEDED);
          }
          remaining = notFull.awaitNanos(remaining);
        }
        ensureOpen();
        validator.accept(message);
        messages.addLast(message);
        notEmpty.signal();
      } finally {
        lock.unlock();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      failInterrupted();
      throw delivery(OutboundDeliveryException.Reason.INTERRUPTED);
    }
  }

  public Optional<OutboundMessage> poll(Duration timeout) {
    requireNonNegativeBounded(timeout, "timeout");
    try {
      lock.lockInterruptibly();
      try {
        long remaining = timeout.toNanos();
        while (messages.isEmpty()) {
          if (state != State.OPEN || validator.isTerminal() || remaining <= 0) {
            return Optional.empty();
          }
          remaining = notEmpty.awaitNanos(remaining);
        }
        OutboundMessage message = messages.removeFirst();
        notFull.signal();
        return Optional.of(message);
      } finally {
        lock.unlock();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      failInterrupted();
      throw delivery(OutboundDeliveryException.Reason.INTERRUPTED);
    }
  }

  public TurnCancellation cancellation() {
    return cancellation.token();
  }

  public boolean disconnect() {
    lock.lock();
    try {
      if (state == State.DISCONNECTED || state == State.SHUTDOWN) {
        return false;
      }
      transition(State.DISCONNECTED, TurnCancellationCode.CHANNEL_DISCONNECTED, true);
      return true;
    } finally {
      lock.unlock();
    }
  }

  public boolean shutdown() {
    lock.lock();
    try {
      if (state == State.SHUTDOWN) {
        return false;
      }
      transition(State.SHUTDOWN, TurnCancellationCode.SHUTDOWN, true);
      return true;
    } finally {
      lock.unlock();
    }
  }

  public int size() {
    lock.lock();
    try {
      return messages.size();
    } finally {
      lock.unlock();
    }
  }

  public boolean isTerminal() {
    return validator.isTerminal();
  }

  private void validate(OutboundMessage message) {
    try {
      validator.validateNext(message);
    } catch (IllegalArgumentException | IllegalStateException invalid) {
      throw delivery(OutboundDeliveryException.Reason.INVALID_MESSAGE);
    }
  }

  private void ensureOpen() {
    if (state != State.OPEN) {
      throw delivery(state.deliveryReason);
    }
  }

  private void failInterrupted() {
    lock.lock();
    try {
      if (state == State.OPEN) {
        transition(State.INTERRUPTED, TurnCancellationCode.REQUESTED, false);
      }
    } finally {
      lock.unlock();
    }
  }

  private void transition(State next, TurnCancellationCode code, boolean clear) {
    state = next;
    cancellation.cancel(code);
    if (clear) {
      messages.clear();
    }
    notEmpty.signalAll();
    notFull.signalAll();
  }

  private static OutboundDeliveryException delivery(OutboundDeliveryException.Reason reason) {
    return new OutboundDeliveryException(reason);
  }

  private static void requirePositiveBounded(Duration duration, String field) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(field + " 必须为正数");
    }
    if (duration.compareTo(MAX_WAIT) > 0) {
      throw new IllegalArgumentException(field + " 超过上限");
    }
  }

  private static void requireNonNegativeBounded(Duration duration, String field) {
    if (duration == null || duration.isNegative()) {
      throw new IllegalArgumentException(field + " 不能为负数");
    }
    if (duration.compareTo(MAX_WAIT) > 0) {
      throw new IllegalArgumentException(field + " 超过上限");
    }
  }

  private enum State {
    OPEN(null),
    BACKPRESSURED(OutboundDeliveryException.Reason.BACKPRESSURE_EXCEEDED),
    DISCONNECTED(OutboundDeliveryException.Reason.CHANNEL_DISCONNECTED),
    INTERRUPTED(OutboundDeliveryException.Reason.INTERRUPTED),
    SHUTDOWN(OutboundDeliveryException.Reason.SHUTDOWN);

    private final OutboundDeliveryException.Reason deliveryReason;

    State(OutboundDeliveryException.Reason deliveryReason) {
      this.deliveryReason = deliveryReason;
    }
  }
}
