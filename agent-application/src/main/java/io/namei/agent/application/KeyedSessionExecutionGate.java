package io.namei.agent.application;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public final class KeyedSessionExecutionGate implements SessionExecutionGate {
  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
  private final Duration waitTimeout;

  public KeyedSessionExecutionGate(Duration waitTimeout) {
    this.waitTimeout = Objects.requireNonNull(waitTimeout, "waitTimeout");
  }

  @Override
  public <T> T execute(String sessionId, Supplier<T> action) {
    Entry entry =
        entries.compute(
            sessionId,
            (key, current) -> {
              Entry selected = current == null ? new Entry() : current;
              selected.references.incrementAndGet();
              return selected;
            });
    boolean acquired = false;
    try {
      acquired = entry.lock.tryLock(waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!acquired) {
        throw new SessionLockTimeoutException(sessionId);
      }
      return action.get();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new SessionLockTimeoutException(sessionId);
    } finally {
      if (acquired) {
        entry.lock.unlock();
      }
      entries.compute(
          sessionId,
          (key, current) -> {
            if (current != entry) {
              return current;
            }
            return entry.references.decrementAndGet() == 0 ? null : entry;
          });
    }
  }

  int activeEntryCount() {
    return entries.size();
  }

  private static final class Entry {
    private final ReentrantLock lock = new ReentrantLock(true);
    private final AtomicInteger references = new AtomicInteger();
  }
}
