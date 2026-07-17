package io.namei.agent.application.control;

import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.channel.OutboundMessageType;
import io.namei.agent.kernel.channel.TurnCancellationCode;
import io.namei.agent.kernel.control.ControlCancelResult;
import io.namei.agent.kernel.control.ControlEventProjection;
import io.namei.agent.kernel.control.ControlStableCode;
import io.namei.agent.kernel.control.ControlTerminalKind;
import io.namei.agent.kernel.control.ControlTurnRef;
import io.namei.agent.kernel.control.ControlTurnState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

public final class ActiveTurnRegistry implements AutoCloseable {
  private static final Pattern CHANNEL = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

  private final Clock clock;
  private final ControlTurnRefGenerator references;
  private final int maxActiveTurns;
  private final Duration terminalRetention;
  private final int maxTerminalTombstones;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<ControlTurnRef, Entry> active = new HashMap<>();
  private final Map<ControlTurnRef, Tombstone> tombstones = new HashMap<>();
  private boolean accepting = true;
  private ControlEventHub eventHub;

  public ActiveTurnRegistry(
      Clock clock,
      ControlTurnRefGenerator references,
      int maxActiveTurns,
      Duration terminalRetention,
      int maxTerminalTombstones) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.references = Objects.requireNonNull(references, "references");
    if (maxActiveTurns < 1) {
      throw new IllegalArgumentException("控制面活动 Turn 容量必须为正数");
    }
    if (terminalRetention == null || terminalRetention.isZero() || terminalRetention.isNegative()) {
      throw new IllegalArgumentException("控制面 Tombstone 保留时间必须为正数");
    }
    if (maxTerminalTombstones < 1) {
      throw new IllegalArgumentException("控制面 Tombstone 容量必须为正数");
    }
    this.maxActiveTurns = maxActiveTurns;
    this.terminalRetention = terminalRetention;
    this.maxTerminalTombstones = maxTerminalTombstones;
  }

  public ActiveTurnRegistration register(
      String channel, ControlCancellationHandle cancellation, Instant startedAt) {
    requireChannel(channel);
    Objects.requireNonNull(cancellation, "cancellation");
    Objects.requireNonNull(startedAt, "startedAt");
    lock.lock();
    try {
      cleanupExpired(clock.instant());
      if (!accepting || active.size() >= maxActiveTurns) {
        return ActiveTurnRegistration.noOp();
      }
      ControlTurnRef turnRef = Objects.requireNonNull(references.next(), "turnRef");
      if (active.containsKey(turnRef) || tombstones.containsKey(turnRef)) {
        return ActiveTurnRegistration.noOp();
      }
      ControlTurnState state =
          cancellation.isCancellationRequested()
              ? ControlTurnState.CANCELLATION_REQUESTED
              : ControlTurnState.ACTIVE;
      active.put(turnRef, new Entry(turnRef, channel, cancellation, startedAt, state));
      return new ActiveTurnRegistration(this, turnRef);
    } finally {
      lock.unlock();
    }
  }

  public ActiveTurnRegistrySnapshot snapshot() {
    lock.lock();
    try {
      cleanupExpired(clock.instant());
      ArrayList<ActiveTurnSnapshot> snapshots = new ArrayList<>(active.size());
      for (Entry entry : active.values()) {
        snapshots.add(entry.snapshot());
      }
      snapshots.sort(
          Comparator.comparing(ActiveTurnSnapshot::startedAt)
              .thenComparing(snapshot -> snapshot.turnRef().value()));
      return new ActiveTurnRegistrySnapshot(
          snapshots, tombstones.size(), !accepting || active.size() >= maxActiveTurns);
    } finally {
      lock.unlock();
    }
  }

  public ControlCancellationOutcome cancel(ControlTurnRef turnRef) {
    Objects.requireNonNull(turnRef, "turnRef");
    Entry target;
    lock.lock();
    try {
      cleanupExpired(clock.instant());
      target = active.get(turnRef);
      if (target == null) {
        return tombstones.containsKey(turnRef)
            ? ControlCancellationOutcome.absent(ControlCancelResult.ALREADY_TERMINAL)
            : ControlCancellationOutcome.absent(ControlCancelResult.NOT_FOUND);
      }
    } finally {
      lock.unlock();
    }

    boolean firstWriter = target.cancellation.requestCancellation();
    TurnCancellationCode reason = target.cancellation.reason();

    lock.lock();
    try {
      cleanupExpired(clock.instant());
      if (active.get(turnRef) != target) {
        return tombstones.containsKey(turnRef)
            ? ControlCancellationOutcome.absent(ControlCancelResult.ALREADY_TERMINAL)
            : ControlCancellationOutcome.absent(ControlCancelResult.NOT_FOUND);
      }
      target.state = ControlTurnState.CANCELLATION_REQUESTED;
      if (firstWriter) {
        return ControlCancellationOutcome.active(
            ControlCancelResult.CANCELLATION_REQUESTED, target.state);
      }
      return ControlCancellationOutcome.active(
          reason == TurnCancellationCode.REQUESTED
              ? ControlCancelResult.ALREADY_REQUESTED
              : ControlCancelResult.ALREADY_CANCELLED,
          target.state);
    } finally {
      lock.unlock();
    }
  }

  public Optional<ControlTerminalKind> terminalKind(ControlTurnRef turnRef) {
    Objects.requireNonNull(turnRef, "turnRef");
    lock.lock();
    try {
      cleanupExpired(clock.instant());
      Tombstone tombstone = tombstones.get(turnRef);
      return tombstone == null ? Optional.empty() : Optional.of(tombstone.kind);
    } finally {
      lock.unlock();
    }
  }

  void attachEventHub(ControlEventHub hub) {
    Objects.requireNonNull(hub, "hub");
    lock.lock();
    try {
      if (eventHub != null && eventHub != hub) {
        throw new IllegalStateException("控制面 Registry 只能绑定一个 Event Hub");
      }
      eventHub = hub;
    } finally {
      lock.unlock();
    }
  }

  ControlSubscription subscribe(
      ControlEventHub hub, ControlTurnRef turnRef, String actorRef, Instant subscribedAt) {
    lock.lock();
    try {
      requireEventHub(hub);
      cleanupExpired(clock.instant());
      if (hub.isClosedLocked()) {
        throw new ControlSubscriptionException(ControlSubscriptionException.Reason.SHUTTING_DOWN);
      }
      Entry entry = active.get(turnRef);
      if (entry == null) {
        throw new ControlSubscriptionException(
            tombstones.containsKey(turnRef)
                ? ControlSubscriptionException.Reason.ALREADY_TERMINAL
                : ControlSubscriptionException.Reason.TURN_NOT_FOUND);
      }
      ControlSubscription subscription =
          hub.createLocked(turnRef, entry.state, entry.lastSequence, actorRef, subscribedAt);
      entry.subscriptions.add(subscription);
      return subscription;
    } finally {
      lock.unlock();
    }
  }

  void closeSubscription(
      ControlEventHub hub,
      ControlSubscription subscription,
      ControlSubscriptionCloseReason reason,
      boolean retainQueued) {
    Objects.requireNonNull(subscription, "subscription");
    Objects.requireNonNull(reason, "reason");
    lock.lock();
    try {
      requireEventHub(hub);
      detachSubscriptionLocked(hub, subscription, reason, retainQueued);
    } finally {
      lock.unlock();
    }
  }

  void closeActorSubscriptions(ControlEventHub hub, String actorRef) {
    lock.lock();
    try {
      requireEventHub(hub);
      for (ControlSubscription subscription : hub.actorSubscriptionsLocked(actorRef)) {
        detachSubscriptionLocked(
            hub, subscription, ControlSubscriptionCloseReason.SESSION_REVOKED, false);
      }
    } finally {
      lock.unlock();
    }
  }

  int subscriberCount(ControlEventHub hub) {
    lock.lock();
    try {
      requireEventHub(hub);
      return hub.subscriberCountLocked();
    } finally {
      lock.unlock();
    }
  }

  void closeEventHub(ControlEventHub hub) {
    lock.lock();
    try {
      requireEventHub(hub);
      closeEventHubLocked(hub);
    } finally {
      lock.unlock();
    }
  }

  void observe(ControlTurnRef turnRef, OutboundMessage message) {
    Objects.requireNonNull(message, "message");
    lock.lock();
    try {
      Entry entry = active.get(turnRef);
      if (entry == null) {
        return;
      }
      validateNext(entry, message);
      ControlEventProjection projection = ControlEventProjection.from(turnRef, message);
      entry.lastSequence = message.sequence();
      if (eventHub != null && !eventHub.isClosedLocked()) {
        for (ControlSubscription subscription : List.copyOf(entry.subscriptions)) {
          if (subscription.offer(projection) == ControlSubscription.OfferResult.FULL) {
            detachSubscriptionLocked(
                eventHub, subscription, ControlSubscriptionCloseReason.SLOW_CONSUMER, false);
          }
        }
      }
      ControlTerminalKind terminalKind = terminalKind(message.type());
      if (terminalKind != null && active.remove(turnRef, entry)) {
        closeEntrySubscriptionsLocked(entry, ControlSubscriptionCloseReason.TERMINAL, true);
        addTombstone(
            turnRef, terminalKind, message.code(), clock.instant().plus(terminalRetention));
      }
    } finally {
      lock.unlock();
    }
  }

  void closeWithoutTerminal(ControlTurnRef turnRef) {
    lock.lock();
    try {
      Entry removed = active.remove(turnRef);
      if (removed != null) {
        closeEntrySubscriptionsLocked(removed, ControlSubscriptionCloseReason.SOURCE_ENDED, false);
        addTombstone(
            turnRef,
            ControlTerminalKind.SOURCE_ENDED,
            ControlStableCode.CONTROL_SOURCE_ENDED.name(),
            clock.instant().plus(terminalRetention));
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void close() {
    lock.lock();
    try {
      accepting = false;
      if (eventHub != null) {
        closeEventHubLocked(eventHub);
      }
      active.clear();
      tombstones.clear();
    } finally {
      lock.unlock();
    }
  }

  private void closeEventHubLocked(ControlEventHub hub) {
    if (hub.isClosedLocked()) {
      return;
    }
    for (ControlSubscription subscription : hub.subscriptionsLocked()) {
      detachSubscriptionLocked(hub, subscription, ControlSubscriptionCloseReason.SHUTDOWN, false);
    }
    hub.markClosedLocked();
  }

  private void closeEntrySubscriptionsLocked(
      Entry entry, ControlSubscriptionCloseReason reason, boolean retainQueued) {
    if (eventHub == null) {
      return;
    }
    for (ControlSubscription subscription : List.copyOf(entry.subscriptions)) {
      eventHub.detachLocked(subscription);
      entry.subscriptions.remove(subscription);
      subscription.closeFromOwner(reason, retainQueued);
    }
  }

  private void detachSubscriptionLocked(
      ControlEventHub hub,
      ControlSubscription subscription,
      ControlSubscriptionCloseReason reason,
      boolean retainQueued) {
    if (hub.detachLocked(subscription)) {
      Entry entry = active.get(subscription.opening().turnRef());
      if (entry != null) {
        entry.subscriptions.remove(subscription);
      }
    }
    subscription.closeFromOwner(reason, retainQueued);
  }

  private void requireEventHub(ControlEventHub hub) {
    if (eventHub != hub) {
      throw new IllegalArgumentException("控制面 Event Hub 不属于当前 Registry");
    }
  }

  private void addTombstone(
      ControlTurnRef turnRef, ControlTerminalKind kind, String code, Instant expiresAt) {
    tombstones.put(turnRef, new Tombstone(kind, code, expiresAt));
    if (tombstones.size() <= maxTerminalTombstones) {
      return;
    }
    ControlTurnRef oldest =
        tombstones.entrySet().stream()
            .min(
                Map.Entry.<ControlTurnRef, Tombstone>comparingByValue(
                        Comparator.comparing(Tombstone::expiresAt))
                    .thenComparing(entry -> entry.getKey().value()))
            .orElseThrow()
            .getKey();
    tombstones.remove(oldest);
  }

  private void cleanupExpired(Instant now) {
    tombstones.entrySet().removeIf(entry -> !entry.getValue().expiresAt.isAfter(now));
  }

  private static void validateNext(Entry entry, OutboundMessage message) {
    if (entry.lastSequence == null) {
      if (message.type() != OutboundMessageType.TURN_STARTED || message.sequence() != 0) {
        throw new IllegalArgumentException("控制面观察必须从 TURN_STARTED 开始");
      }
      return;
    }
    if (entry.lastSequence == Long.MAX_VALUE || message.sequence() != entry.lastSequence + 1) {
      throw new IllegalArgumentException("控制面观察 Message sequence 不连续");
    }
    if (message.type() == OutboundMessageType.TURN_STARTED) {
      throw new IllegalArgumentException("控制面观察不能重复 TURN_STARTED");
    }
  }

  private static ControlTerminalKind terminalKind(OutboundMessageType type) {
    return switch (type) {
      case TURN_COMPLETED -> ControlTerminalKind.COMPLETED;
      case TURN_CANCELLED -> ControlTerminalKind.CANCELLED;
      case TURN_FAILED -> ControlTerminalKind.FAILED;
      case TURN_STARTED, CONTENT_DELTA -> null;
    };
  }

  private static void requireChannel(String channel) {
    if (channel == null || !CHANNEL.matcher(channel).matches()) {
      throw new IllegalArgumentException("控制面 Channel 无效");
    }
  }

  private static final class Entry {
    private final ControlTurnRef turnRef;
    private final String channel;
    private final ControlCancellationHandle cancellation;
    private final Instant startedAt;
    private final HashSet<ControlSubscription> subscriptions = new HashSet<>();
    private ControlTurnState state;
    private Long lastSequence;

    private Entry(
        ControlTurnRef turnRef,
        String channel,
        ControlCancellationHandle cancellation,
        Instant startedAt,
        ControlTurnState state) {
      this.turnRef = turnRef;
      this.channel = channel;
      this.cancellation = cancellation;
      this.startedAt = startedAt;
      this.state = state;
    }

    private ActiveTurnSnapshot snapshot() {
      return new ActiveTurnSnapshot(
          turnRef, channel, state, startedAt, lastSequence, subscriptions.size());
    }
  }

  private record Tombstone(ControlTerminalKind kind, String code, Instant expiresAt) {
    private Tombstone {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(expiresAt, "expiresAt");
    }
  }
}
