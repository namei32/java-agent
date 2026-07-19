package io.namei.agent.bootstrap.control;

import io.namei.agent.kernel.control.HistoryDetailRef;
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

/** Bounded, actor/Scope-bound, one-time detail reference store. */
final class ControlHistoryDetailReferenceStore {
  static final Duration TTL = Duration.ofMinutes(1);
  static final int MAXIMUM = 128;
  private static final Pattern ACTOR = Pattern.compile("[A-Za-z0-9_-]{22}");

  private final Clock clock;
  private final ControlRandomSource random;
  private final ReentrantLock lock = new ReentrantLock();
  private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

  ControlHistoryDetailReferenceStore(Clock clock, ControlRandomSource random) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.random = Objects.requireNonNull(random, "random");
  }

  HistoryDetailRef issue(String actorRef, HistoryScopeCapability scope) {
    requireActor(actorRef);
    Objects.requireNonNull(scope, "scope");
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
        HistoryDetailRef reference = nextReference();
        if (!entries.containsKey(reference.value())) {
          entries.put(reference.value(), new Entry(actorRef, scope, now.plus(TTL)));
          return reference;
        }
      }
      throw new IllegalStateException("控制历史详情引用随机源冲突");
    } finally {
      lock.unlock();
    }
  }

  Optional<Entry> take(HistoryDetailRef reference, String actorRef) {
    Objects.requireNonNull(reference, "reference");
    if (!validActor(actorRef)) {
      return Optional.empty();
    }
    lock.lock();
    try {
      removeExpired(clock.instant());
      Entry entry = entries.get(reference.value());
      if (entry == null || !entry.actorRef.equals(actorRef)) {
        return Optional.empty();
      }
      entries.remove(reference.value());
      return Optional.of(entry);
    } finally {
      lock.unlock();
    }
  }

  private HistoryDetailRef nextReference() {
    byte[] bytes = Objects.requireNonNull(random.nextBytes(16), "history detail random value");
    if (bytes.length != 16) {
      throw new IllegalStateException("控制历史详情随机源长度无效");
    }
    try {
      return HistoryDetailRef.fromBytes(bytes);
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

  record Entry(String actorRef, HistoryScopeCapability scope, Instant expiresAt) {
    Entry {
      actorRef = Objects.requireNonNull(actorRef, "actorRef");
      scope = Objects.requireNonNull(scope, "scope");
      expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
      return "ControlHistoryDetailReferenceEntry[redacted]";
    }
  }
}
