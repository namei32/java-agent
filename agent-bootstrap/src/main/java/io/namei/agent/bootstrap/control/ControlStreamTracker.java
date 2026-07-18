package io.namei.agent.bootstrap.control;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

final class ControlStreamTracker {
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition drained = lock.newCondition();
  private boolean accepting = true;
  private int active;

  Optional<Lease> open() {
    lock.lock();
    try {
      if (!accepting) {
        return Optional.empty();
      }
      active++;
      return Optional.of(new Lease(this));
    } finally {
      lock.unlock();
    }
  }

  void stopAccepting() {
    lock.lock();
    try {
      accepting = false;
    } finally {
      lock.unlock();
    }
  }

  boolean awaitDrained(Duration timeout) {
    if (timeout == null || timeout.isNegative()) {
      throw new IllegalArgumentException("控制面关闭等待时间无效");
    }
    try {
      lock.lockInterruptibly();
      try {
        long remaining = timeout.toNanos();
        while (active > 0 && remaining > 0) {
          remaining = drained.awaitNanos(remaining);
        }
        return active == 0;
      } finally {
        lock.unlock();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  int activeCount() {
    lock.lock();
    try {
      return active;
    } finally {
      lock.unlock();
    }
  }

  private void release() {
    lock.lock();
    try {
      if (active < 1) {
        throw new IllegalStateException("控制面活动流计数下溢");
      }
      active--;
      if (active == 0) {
        drained.signalAll();
      }
    } finally {
      lock.unlock();
    }
  }

  static final class Lease implements AutoCloseable {
    private final ControlStreamTracker owner;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Lease(ControlStreamTracker owner) {
      this.owner = owner;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        owner.release();
      }
    }
  }
}
