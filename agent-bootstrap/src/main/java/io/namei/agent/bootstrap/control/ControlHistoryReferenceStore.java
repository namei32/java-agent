package io.namei.agent.bootstrap.control;

import io.namei.agent.application.control.ControlTerminalTurnSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Bounded, process-local mappings from terminal Turn metadata to opaque references.
 *
 * <p>The references are deliberately not request parameters in C2-A. Resolution exists only for a
 * later separately approved contract and is always actor-bound.
 */
final class ControlHistoryReferenceStore {
  static final Duration TTL = Duration.ofMinutes(1);
  static final int MAXIMUM = 128;
  private static final int REFERENCE_BYTES = 16;
  private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9_-]{22}");
  private static final Pattern ACTOR = Pattern.compile("[A-Za-z0-9_-]{22}");

  private final Clock clock;
  private final ControlRandomSource random;
  private final Duration ttl;
  private final int maximum;
  private final ReentrantLock lock = new ReentrantLock();
  private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
  private final Map<ActorTurnKey, String> referencesByTurn = new LinkedHashMap<>();

  ControlHistoryReferenceStore(Clock clock, ControlRandomSource random) {
    this(clock, random, TTL, MAXIMUM);
  }

  ControlHistoryReferenceStore(Clock clock, ControlRandomSource random, Duration ttl, int maximum) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.random = Objects.requireNonNull(random, "random");
    if (ttl == null
        || ttl.isZero()
        || ttl.isNegative()
        || ttl.compareTo(Duration.ofMinutes(1)) > 0) {
      throw new IllegalArgumentException("控制历史引用 TTL 无效");
    }
    if (maximum < 1 || maximum > MAXIMUM) {
      throw new IllegalArgumentException("控制历史引用容量无效");
    }
    this.ttl = ttl;
    this.maximum = maximum;
  }

  String issue(String actorRef, ControlTerminalTurnSnapshot snapshot) {
    requireActor(actorRef);
    snapshot = Objects.requireNonNull(snapshot, "snapshot");
    Instant now = clock.instant();
    ActorTurnKey key = new ActorTurnKey(actorRef, snapshot.turnRef());
    lock.lock();
    try {
      removeExpired(now);
      String existing = referencesByTurn.get(key);
      if (existing != null) {
        return existing;
      }
      while (entries.size() >= maximum) {
        removeOldest();
      }
      for (int attempt = 0; attempt < 4; attempt++) {
        String reference = nextReference();
        if (!entries.containsKey(reference) && !matchesInternalTurnReference(reference, snapshot)) {
          entries.put(reference, new Entry(actorRef, now.plus(ttl), snapshot));
          referencesByTurn.put(key, reference);
          return reference;
        }
      }
      throw new IllegalStateException("控制历史引用随机源冲突");
    } finally {
      lock.unlock();
    }
  }

  Optional<ControlTerminalTurnSnapshot> resolve(String reference, String actorRef) {
    if (reference == null || !REFERENCE.matcher(reference).matches() || !isActor(actorRef)) {
      return Optional.empty();
    }
    lock.lock();
    try {
      removeExpired(clock.instant());
      Entry entry = entries.get(reference);
      if (entry == null || !entry.actorRef.equals(actorRef)) {
        return Optional.empty();
      }
      return Optional.of(entry.snapshot);
    } finally {
      lock.unlock();
    }
  }

  private void removeOldest() {
    Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
    Map.Entry<String, Entry> oldest = iterator.next();
    iterator.remove();
    referencesByTurn.remove(
        new ActorTurnKey(oldest.getValue().actorRef, oldest.getValue().snapshot.turnRef()));
  }

  private boolean matchesInternalTurnReference(
      String candidate, ControlTerminalTurnSnapshot current) {
    return candidate.equals(current.turnRef().value())
        || referencesByTurn.keySet().stream()
            .anyMatch(key -> candidate.equals(key.turnRef().value()));
  }

  private String nextReference() {
    byte[] value =
        Objects.requireNonNull(random.nextBytes(REFERENCE_BYTES), "history reference random value");
    if (value.length != REFERENCE_BYTES) {
      throw new IllegalStateException("控制历史引用随机源长度无效");
    }
    try {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    } finally {
      java.util.Arrays.fill(value, (byte) 0);
    }
  }

  private void removeExpired(Instant now) {
    Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
    while (iterator.hasNext()) {
      Entry entry = iterator.next().getValue();
      if (!now.isBefore(entry.expiresAt)) {
        iterator.remove();
        referencesByTurn.remove(new ActorTurnKey(entry.actorRef, entry.snapshot.turnRef()));
      }
    }
  }

  private static void requireActor(String actorRef) {
    if (!isActor(actorRef)) {
      throw new IllegalArgumentException("控制历史主体无效");
    }
  }

  private static boolean isActor(String actorRef) {
    return actorRef != null && ACTOR.matcher(actorRef).matches();
  }

  private record ActorTurnKey(
      String actorRef, io.namei.agent.kernel.control.ControlTurnRef turnRef) {
    private ActorTurnKey {
      actorRef = Objects.requireNonNull(actorRef, "actorRef");
      turnRef = Objects.requireNonNull(turnRef, "turnRef");
    }
  }

  private record Entry(String actorRef, Instant expiresAt, ControlTerminalTurnSnapshot snapshot) {
    private Entry {
      actorRef = Objects.requireNonNull(actorRef, "actorRef");
      expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
      snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }
  }
}
