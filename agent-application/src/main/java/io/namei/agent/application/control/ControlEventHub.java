package io.namei.agent.application.control;

import io.namei.agent.kernel.control.ControlTurnRef;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class ControlEventHub implements AutoCloseable {
  public static final int MAX_SUBSCRIBERS = 64;
  public static final int MAX_BUFFER_CAPACITY = 1024;
  public static final Duration MAX_LIFETIME = Duration.ofHours(1);

  private static final Pattern ACTOR_REF = Pattern.compile("[A-Za-z0-9_-]{1,128}");

  private final ActiveTurnRegistry registry;
  private final Clock clock;
  private final int maxSubscribers;
  private final int bufferCapacity;
  private final Duration lifetime;
  private final Set<ControlSubscription> subscriptions = new HashSet<>();
  private final Map<String, Set<ControlSubscription>> byActor = new HashMap<>();
  private boolean closed;

  public ControlEventHub(
      ActiveTurnRegistry registry,
      Clock clock,
      int maxSubscribers,
      int bufferCapacity,
      Duration lifetime) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.clock = Objects.requireNonNull(clock, "clock");
    requireRange(maxSubscribers, 1, MAX_SUBSCRIBERS, "控制面 Subscriber 容量");
    requireRange(bufferCapacity, 1, MAX_BUFFER_CAPACITY, "控制面 Subscriber Buffer 容量");
    if (lifetime == null
        || lifetime.isZero()
        || lifetime.isNegative()
        || lifetime.compareTo(MAX_LIFETIME) > 0) {
      throw new IllegalArgumentException("控制面订阅生命期无效");
    }
    this.maxSubscribers = maxSubscribers;
    this.bufferCapacity = bufferCapacity;
    this.lifetime = lifetime;
    registry.attachEventHub(this);
  }

  public ControlSubscription subscribe(ControlTurnRef turnRef, String actorRef) {
    Objects.requireNonNull(turnRef, "turnRef");
    requireActorRef(actorRef);
    return registry.subscribe(this, turnRef, actorRef, clock.instant());
  }

  public void closeActor(String actorRef) {
    requireActorRef(actorRef);
    registry.closeActorSubscriptions(this, actorRef);
  }

  public int subscriberCount() {
    return registry.subscriberCount(this);
  }

  @Override
  public void close() {
    registry.closeEventHub(this);
  }

  ControlSubscription createLocked(
      ControlTurnRef turnRef,
      io.namei.agent.kernel.control.ControlTurnState state,
      Long lastSequence,
      String actorRef,
      Instant subscribedAt) {
    if (closed) {
      throw new ControlSubscriptionException(ControlSubscriptionException.Reason.SHUTTING_DOWN);
    }
    if (subscriptions.size() >= maxSubscribers) {
      throw new ControlSubscriptionException(ControlSubscriptionException.Reason.CAPACITY_EXCEEDED);
    }
    ControlStreamOpening opening =
        new ControlStreamOpening(turnRef, state, lastSequence, subscribedAt, false);
    ControlSubscription subscription =
        new ControlSubscription(
            this, opening, actorRef, clock, subscribedAt.plus(lifetime), bufferCapacity);
    subscriptions.add(subscription);
    byActor.computeIfAbsent(actorRef, ignored -> new HashSet<>()).add(subscription);
    return subscription;
  }

  boolean detachLocked(ControlSubscription subscription) {
    if (!subscriptions.remove(subscription)) {
      return false;
    }
    Set<ControlSubscription> actorSubscriptions = byActor.get(subscription.actorRef());
    if (actorSubscriptions != null) {
      actorSubscriptions.remove(subscription);
      if (actorSubscriptions.isEmpty()) {
        byActor.remove(subscription.actorRef());
      }
    }
    return true;
  }

  List<ControlSubscription> actorSubscriptionsLocked(String actorRef) {
    Set<ControlSubscription> actorSubscriptions = byActor.get(actorRef);
    return actorSubscriptions == null ? List.of() : List.copyOf(actorSubscriptions);
  }

  List<ControlSubscription> subscriptionsLocked() {
    return List.copyOf(subscriptions);
  }

  int subscriberCountLocked() {
    return subscriptions.size();
  }

  boolean isClosedLocked() {
    return closed;
  }

  void markClosedLocked() {
    closed = true;
  }

  void closeSubscription(
      ControlSubscription subscription,
      ControlSubscriptionCloseReason reason,
      boolean retainQueued) {
    registry.closeSubscription(this, subscription, reason, retainQueued);
  }

  private static void requireActorRef(String actorRef) {
    if (actorRef == null || !ACTOR_REF.matcher(actorRef).matches()) {
      throw new IllegalArgumentException("控制面 Actor 引用无效");
    }
  }

  private static void requireRange(int value, int minimum, int maximum, String field) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(field + "必须在 " + minimum + ".." + maximum + " 之间");
    }
  }

  @Override
  public String toString() {
    return "ControlEventHub[subscriberCount=" + subscriberCount() + ", sensitiveFields=<redacted>]";
  }
}
