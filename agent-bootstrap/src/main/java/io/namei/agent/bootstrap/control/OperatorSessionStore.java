package io.namei.agent.bootstrap.control;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class OperatorSessionStore implements AutoCloseable {
  private static final int TOKEN_BYTES = 32;
  private static final int ACTOR_BYTES = 16;
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
  private static final Pattern ACTOR = Pattern.compile("[A-Za-z0-9_-]{22}");

  private final Clock clock;
  private final ControlRandomSource random;
  private final Duration ttl;
  private final int maximum;
  private final Consumer<String> actorRevoker;
  private final ReentrantLock lock = new ReentrantLock();
  private final List<Entry> entries = new ArrayList<>();
  private boolean accepting = true;

  public OperatorSessionStore(
      Clock clock,
      ControlRandomSource random,
      Duration ttl,
      int maximum,
      Consumer<String> actorRevoker) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.random = Objects.requireNonNull(random, "random");
    if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofHours(1)) > 0) {
      throw new IllegalArgumentException("控制面 Session TTL 无效");
    }
    if (maximum < 1 || maximum > 16) {
      throw new IllegalArgumentException("控制面 Session 容量无效");
    }
    this.ttl = ttl;
    this.maximum = maximum;
    this.actorRevoker = Objects.requireNonNull(actorRevoker, "actorRevoker");
  }

  public OperatorSessionCreated create() {
    Instant now = clock.instant();
    List<String> expired = new ArrayList<>();
    lock.lock();
    try {
      expired.addAll(removeExpiredLocked(now));
      if (!accepting) {
        throw new IllegalStateException("控制面 Session Store 已关闭");
      }
      if (entries.size() >= maximum) {
        throw new OperatorSessionCapacityException();
      }
      String token = encode(random.nextBytes(TOKEN_BYTES), TOKEN_BYTES, "Token");
      String actorRef = encode(random.nextBytes(ACTOR_BYTES), ACTOR_BYTES, "Actor");
      byte[] digest = digest(token);
      if (containsDigestLocked(digest) || containsActorLocked(actorRef)) {
        java.util.Arrays.fill(digest, (byte) 0);
        throw new IllegalStateException("控制面随机引用发生冲突");
      }
      Instant expiresAt = now.plus(ttl);
      entries.add(new Entry(digest, actorRef, expiresAt));
      return new OperatorSessionCreated(token, "Bearer", expiresAt);
    } finally {
      lock.unlock();
      revokeAll(expired);
    }
  }

  public Optional<OperatorSessionPrincipal> authenticate(String token) {
    if (token == null || !TOKEN.matcher(token).matches()) {
      return Optional.empty();
    }
    byte[] candidate = digest(token);
    List<String> expired;
    OperatorSessionPrincipal principal = null;
    lock.lock();
    try {
      expired = removeExpiredLocked(clock.instant());
      if (accepting) {
        for (Entry entry : entries) {
          if (MessageDigest.isEqual(entry.digest, candidate)) {
            principal = new OperatorSessionPrincipal(entry.actorRef, entry.expiresAt);
          }
        }
      }
    } finally {
      lock.unlock();
      java.util.Arrays.fill(candidate, (byte) 0);
    }
    revokeAll(expired);
    return Optional.ofNullable(principal);
  }

  public boolean revoke(String actorRef) {
    if (actorRef == null || !ACTOR.matcher(actorRef).matches()) {
      return false;
    }
    boolean removed = false;
    lock.lock();
    try {
      for (int index = 0; index < entries.size(); index++) {
        Entry entry = entries.get(index);
        if (entry.actorRef.equals(actorRef)) {
          entries.remove(index);
          entry.clear();
          removed = true;
          break;
        }
      }
    } finally {
      lock.unlock();
    }
    if (removed) {
      revokeSafely(actorRef);
    }
    return removed;
  }

  public int size() {
    lock.lock();
    try {
      return entries.size();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void close() {
    List<String> actors = new ArrayList<>();
    lock.lock();
    try {
      if (!accepting && entries.isEmpty()) {
        return;
      }
      accepting = false;
      for (Entry entry : entries) {
        actors.add(entry.actorRef);
        entry.clear();
      }
      entries.clear();
    } finally {
      lock.unlock();
    }
    revokeAll(actors);
  }

  private List<String> removeExpiredLocked(Instant now) {
    List<String> actors = new ArrayList<>();
    for (int index = entries.size() - 1; index >= 0; index--) {
      Entry entry = entries.get(index);
      if (!now.isBefore(entry.expiresAt)) {
        entries.remove(index);
        actors.add(entry.actorRef);
        entry.clear();
      }
    }
    return actors;
  }

  private boolean containsDigestLocked(byte[] candidate) {
    boolean found = false;
    for (Entry entry : entries) {
      found |= MessageDigest.isEqual(entry.digest, candidate);
    }
    return found;
  }

  private boolean containsActorLocked(String actorRef) {
    return entries.stream().anyMatch(entry -> entry.actorRef.equals(actorRef));
  }

  private void revokeAll(List<String> actors) {
    actors.forEach(this::revokeSafely);
  }

  private void revokeSafely(String actorRef) {
    try {
      actorRevoker.accept(actorRef);
    } catch (RuntimeException ignored) {
      // Session 生命周期不能因订阅清理观察失败而改变。
    }
  }

  private static String encode(byte[] value, int expectedSize, String field) {
    Objects.requireNonNull(value, field);
    if (value.length != expectedSize) {
      throw new IllegalStateException("控制面 " + field + " 随机源长度无效");
    }
    String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    java.util.Arrays.fill(value, (byte) 0);
    return encoded;
  }

  private static byte[] digest(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JDK 缺少 SHA-256");
    }
  }

  @Override
  public String toString() {
    return "OperatorSessionStore[size=" + size() + ", sensitiveFields=<redacted>]";
  }

  private static final class Entry {
    private final byte[] digest;
    private final String actorRef;
    private final Instant expiresAt;

    private Entry(byte[] digest, String actorRef, Instant expiresAt) {
      this.digest = digest;
      this.actorRef = actorRef;
      this.expiresAt = expiresAt;
    }

    private void clear() {
      java.util.Arrays.fill(digest, (byte) 0);
    }
  }
}
