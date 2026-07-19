package io.namei.agent.bootstrap.control;

import io.namei.agent.application.control.ControlTerminalTurnSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/** In-memory one-time continuation store for safe terminal metadata only. */
final class ControlHistoryCursorStore {
  static final Duration TTL = Duration.ofMinutes(1);
  static final int MAXIMUM = 128;
  private static final int CURSOR_BYTES = 16;
  private static final Pattern CURSOR = Pattern.compile("[A-Za-z0-9_-]{22}");
  private static final Pattern ACTOR = Pattern.compile("[A-Za-z0-9_-]{22}");

  private final Clock clock;
  private final ControlRandomSource random;
  private final Duration ttl;
  private final int maximum;
  private final ReentrantLock lock = new ReentrantLock();
  private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

  ControlHistoryCursorStore(Clock clock, ControlRandomSource random) {
    this(clock, random, TTL, MAXIMUM);
  }

  ControlHistoryCursorStore(Clock clock, ControlRandomSource random, Duration ttl, int maximum) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.random = Objects.requireNonNull(random, "random");
    if (ttl == null
        || ttl.isZero()
        || ttl.isNegative()
        || ttl.compareTo(Duration.ofMinutes(1)) > 0) {
      throw new IllegalArgumentException("控制历史游标 TTL 无效");
    }
    if (maximum < 1 || maximum > MAXIMUM) {
      throw new IllegalArgumentException("控制历史游标容量无效");
    }
    this.ttl = ttl;
    this.maximum = maximum;
  }

  String issue(String actorRef, List<ControlTerminalTurnSnapshot> remaining) {
    requireActor(actorRef);
    List<ControlTerminalTurnSnapshot> page = List.copyOf(remaining);
    if (page.isEmpty()) {
      return "";
    }
    Instant now = clock.instant();
    lock.lock();
    try {
      removeExpired(now);
      while (entries.size() >= maximum) {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        iterator.next();
        iterator.remove();
      }
      for (int attempt = 0; attempt < 4; attempt++) {
        String cursor = nextCursor();
        if (!entries.containsKey(cursor)) {
          entries.put(cursor, new Entry(actorRef, now.plus(ttl), page));
          return cursor;
        }
      }
      throw new IllegalStateException("控制历史游标随机源冲突");
    } finally {
      lock.unlock();
    }
  }

  Optional<List<ControlTerminalTurnSnapshot>> take(String cursor, String actorRef) {
    if (cursor == null || !CURSOR.matcher(cursor).matches() || !isActor(actorRef)) {
      return Optional.empty();
    }
    lock.lock();
    try {
      removeExpired(clock.instant());
      Entry entry = entries.get(cursor);
      if (entry == null || !entry.actorRef.equals(actorRef)) {
        return Optional.empty();
      }
      entries.remove(cursor);
      return Optional.of(entry.remaining);
    } finally {
      lock.unlock();
    }
  }

  private String nextCursor() {
    byte[] value =
        Objects.requireNonNull(random.nextBytes(CURSOR_BYTES), "history cursor random value");
    if (value.length != CURSOR_BYTES) {
      throw new IllegalStateException("控制历史游标随机源长度无效");
    }
    try {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    } finally {
      java.util.Arrays.fill(value, (byte) 0);
    }
  }

  private void removeExpired(Instant now) {
    entries.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt));
  }

  private static void requireActor(String actorRef) {
    if (!isActor(actorRef)) {
      throw new IllegalArgumentException("控制历史主体无效");
    }
  }

  private static boolean isActor(String actorRef) {
    return actorRef != null && ACTOR.matcher(actorRef).matches();
  }

  private record Entry(
      String actorRef, Instant expiresAt, List<ControlTerminalTurnSnapshot> remaining) {
    private Entry {
      remaining = List.copyOf(remaining);
    }
  }
}
