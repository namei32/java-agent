package io.namei.agent.bootstrap.control;

import io.namei.agent.kernel.control.HistoryDetailRef;
import io.namei.agent.kernel.control.HistoryPageCursor;
import io.namei.agent.kernel.control.HistoryScopeCapability;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/** Bounded one-time continuation store; cursor state is never serialized to an HTTP client. */
final class ControlHistoryDetailCursorStore {
  static final Duration TTL = Duration.ofMinutes(1);
  static final int MAXIMUM = 128;
  private static final Pattern ACTOR = Pattern.compile("[A-Za-z0-9_-]{22}");

  private final Clock clock;
  private final ControlRandomSource random;
  private final ReentrantLock lock = new ReentrantLock();
  private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

  ControlHistoryDetailCursorStore(Clock clock, ControlRandomSource random) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.random = Objects.requireNonNull(random, "random");
  }

  HistoryPageCursor issue(
      String actorRef,
      HistoryScopeCapability scope,
      HistoryDetailRef reference,
      int offset,
      Instant observedAt) {
    requireActor(actorRef);
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(observedAt, "observedAt");
    if (offset < 1 || offset > 1_024) {
      throw new IllegalArgumentException("控制历史详情游标偏移无效");
    }
    Instant now = clock.instant();
    lock.lock();
    try {
      removeExpired(now);
      while (entries.size() >= MAXIMUM) {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        iterator.next();
        iterator.remove();
      }
      for (int attempt = 0; attempt < 4; attempt++) {
        HistoryPageCursor cursor = nextCursor();
        if (!entries.containsKey(cursor.value())) {
          entries.put(
              cursor.value(),
              new Entry(actorRef, scope, reference, offset, observedAt, now.plus(TTL)));
          return cursor;
        }
      }
      throw new IllegalStateException("控制历史详情游标随机源冲突");
    } finally {
      lock.unlock();
    }
  }

  Optional<Entry> take(HistoryPageCursor cursor, String actorRef) {
    Objects.requireNonNull(cursor, "cursor");
    if (!validActor(actorRef)) {
      return Optional.empty();
    }
    lock.lock();
    try {
      removeExpired(clock.instant());
      Entry entry = entries.get(cursor.value());
      if (entry == null || !entry.actorRef.equals(actorRef)) {
        return Optional.empty();
      }
      entries.remove(cursor.value());
      return Optional.of(entry);
    } finally {
      lock.unlock();
    }
  }

  private HistoryPageCursor nextCursor() {
    byte[] bytes =
        Objects.requireNonNull(random.nextBytes(16), "history detail cursor random value");
    if (bytes.length != 16) {
      throw new IllegalStateException("控制历史详情游标随机源长度无效");
    }
    try {
      return HistoryPageCursor.fromBytes(bytes);
    } finally {
      java.util.Arrays.fill(bytes, (byte) 0);
    }
  }

  private void removeExpired(Instant now) {
    entries.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt));
  }

  private static void requireActor(String actorRef) {
    if (!validActor(actorRef)) {
      throw new IllegalArgumentException("控制历史详情主体无效");
    }
  }

  private static boolean validActor(String actorRef) {
    return actorRef != null && ACTOR.matcher(actorRef).matches();
  }

  record Entry(
      String actorRef,
      HistoryScopeCapability scope,
      HistoryDetailRef reference,
      int offset,
      Instant observedAt,
      Instant expiresAt) {
    Entry {
      actorRef = Objects.requireNonNull(actorRef, "actorRef");
      scope = Objects.requireNonNull(scope, "scope");
      reference = Objects.requireNonNull(reference, "reference");
      observedAt = Objects.requireNonNull(observedAt, "observedAt");
      expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
      return "ControlHistoryDetailCursorEntry[redacted]";
    }
  }
}
