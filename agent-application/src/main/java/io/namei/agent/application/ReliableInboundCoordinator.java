package io.namei.agent.application;

import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.reliability.ChannelFingerprint;
import io.namei.agent.kernel.channel.reliability.ChannelInstanceId;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerCommand;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerFailureCarrier;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerFailureKind;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerResult;
import io.namei.agent.kernel.channel.reliability.DeliveryEnvelope;
import io.namei.agent.kernel.channel.reliability.DeliveryMessageType;
import io.namei.agent.kernel.channel.reliability.DeliverySourceKind;
import io.namei.agent.kernel.channel.reliability.InboxEventKind;
import io.namei.agent.kernel.port.ChannelLedgerPort;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ReliableInboundCoordinator {
  private static final String TURN_WORKER = "reliable-inbound-turn";
  private static final String SESSION_BUSY = "SESSION_BUSY";
  private static final String NO_ACTIVE_TURN = "NO_ACTIVE_TURN";

  private final ChannelLedgerPort ledger;
  private final ReliableTurnProcessor turns;
  private final ReliableTurnStarter starter;
  private final Clock clock;
  private final String ownerId;
  private final ReliableTurnIdGenerator ids;
  private final ReliableInboundSettings settings;
  private final Semaphore permits;
  private final ConcurrentHashMap<ActiveKey, ActiveTurn> activeTurns = new ConcurrentHashMap<>();
  private final Object registrations = new Object();

  public ReliableInboundCoordinator(
      ChannelLedgerPort ledger,
      ReliableTurnProcessor turns,
      ReliableTurnStarter starter,
      Clock clock,
      ReliableOwnerProvider owner,
      ReliableTurnIdGenerator ids,
      ReliableInboundSettings settings) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.turns = Objects.requireNonNull(turns, "turns");
    this.starter = Objects.requireNonNull(starter, "starter");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ownerId =
        Objects.requireNonNull(Objects.requireNonNull(owner, "owner").ownerId(), "ownerId");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.permits = new Semaphore(settings.maxConcurrentTurns(), true);
  }

  public ReliableInboundResult handle(ReliableInboundEvent event) {
    Objects.requireNonNull(event, "event");
    return switch (event) {
      case ReliableInboundEvent.Accepted accepted -> handleAccepted(accepted);
      case ReliableInboundEvent.Ignored ignored -> handleIgnored(ignored);
      case ReliableInboundEvent.Control control -> handleControl(control);
    };
  }

  private ReliableInboundResult handleAccepted(ReliableInboundEvent.Accepted event) {
    ActiveKey key = new ActiveKey(event.instance(), event.inbound().sessionId());
    ActiveTurn existing = activeTurns.get(key);
    if (existing != null) {
      if (existing.externalMessageId().equals(event.inbound().messageId())) {
        return recordActiveDuplicate(event);
      }
      return recordFeedback(event, SESSION_BUSY, DeliveryMessageType.SESSION_BUSY);
    }
    if (!permits.tryAcquire()) {
      return recordFeedback(event, SESSION_BUSY, DeliveryMessageType.SESSION_BUSY);
    }

    InboundMessage candidateInbound;
    try {
      String candidateTurnId = Objects.requireNonNull(ids.newTurnId(), "newTurnId");
      candidateInbound = withTurnId(event.inbound(), candidateTurnId);
    } catch (RuntimeException | Error failure) {
      permits.release();
      throw failure;
    }
    var active = new ActiveTurn(event, candidateInbound);
    ActiveTurn raced;
    synchronized (registrations) {
      raced = activeTurns.putIfAbsent(key, active);
    }
    if (raced != null) {
      cleanup(active);
      if (raced.externalMessageId().equals(candidateInbound.messageId())) {
        return recordActiveDuplicate(event);
      }
      return recordFeedback(event, SESSION_BUSY, DeliveryMessageType.SESSION_BUSY);
    }

    boolean scheduled = false;
    try {
      ChannelLedgerResult.Event recorded = record(turnCommand(event, candidateInbound));
      return switch (recorded.status()) {
        case RESERVED_NEW, START_RETRYABLE -> {
          active.prepare(withTurnId(candidateInbound, recorded.turnId()), recorded.revision());
          start(active);
          scheduled = true;
          yield result(ReliableInboundResult.Status.TURN_SCHEDULED, recorded);
        }
        case IN_PROGRESS -> result(ReliableInboundResult.Status.IN_PROGRESS, recorded);
        case ALREADY_TERMINAL -> result(ReliableInboundResult.Status.ALREADY_TERMINAL, recorded);
        case EXECUTION_UNKNOWN -> result(ReliableInboundResult.Status.EXECUTION_UNKNOWN, recorded);
        case EVENT_RECORDED, FEEDBACK_QUEUED -> throw unavailable();
      };
    } finally {
      if (!scheduled) {
        cleanup(active);
      }
    }
  }

  private ReliableInboundResult recordActiveDuplicate(ReliableInboundEvent.Accepted event) {
    String candidateTurnId = Objects.requireNonNull(ids.newTurnId(), "newTurnId");
    ChannelLedgerResult.Event recorded =
        record(turnCommand(event, withTurnId(event.inbound(), candidateTurnId)));
    return switch (recorded.status()) {
      case RESERVED_NEW, START_RETRYABLE, IN_PROGRESS ->
          result(ReliableInboundResult.Status.IN_PROGRESS, recorded);
      case ALREADY_TERMINAL -> result(ReliableInboundResult.Status.ALREADY_TERMINAL, recorded);
      case EXECUTION_UNKNOWN -> result(ReliableInboundResult.Status.EXECUTION_UNKNOWN, recorded);
      case EVENT_RECORDED, FEEDBACK_QUEUED -> throw unavailable();
    };
  }

  private ReliableInboundResult handleIgnored(ReliableInboundEvent.Ignored event) {
    Instant recordedAt = clock.instant();
    String fingerprint =
        ChannelFingerprint.event(
            event.instance(),
            event.externalEventId(),
            event.externalSequence(),
            InboxEventKind.IGNORED,
            event.decisionCode(),
            "");
    ChannelLedgerResult.Event recorded =
        record(
            new ChannelLedgerCommand.RecordEvent(
                event.instance(),
                event.externalEventId(),
                event.externalSequence(),
                fingerprint,
                InboxEventKind.IGNORED,
                event.decisionCode(),
                "",
                null,
                null,
                recordedAt));
    if (recorded.status() != ChannelLedgerResult.InboxStatus.EVENT_RECORDED) {
      throw unavailable();
    }
    return result(ReliableInboundResult.Status.IGNORED_RECORDED, recorded);
  }

  private ReliableInboundResult handleControl(ReliableInboundEvent.Control event) {
    ActiveTurn target = activeTurns.get(new ActiveKey(event.instance(), event.targetSessionId()));
    if (target == null) {
      return recordFeedback(event, NO_ACTIVE_TURN, DeliveryMessageType.NO_ACTIVE_TURN);
    }
    Instant recordedAt = clock.instant();
    String fingerprint =
        ChannelFingerprint.event(
            event.instance(),
            event.externalEventId(),
            event.externalSequence(),
            InboxEventKind.CONTROL,
            event.decisionCode(),
            "");
    ChannelLedgerResult.Event recorded =
        record(
            new ChannelLedgerCommand.RecordEvent(
                event.instance(),
                event.externalEventId(),
                event.externalSequence(),
                fingerprint,
                InboxEventKind.CONTROL,
                event.decisionCode(),
                "",
                null,
                null,
                recordedAt));
    if (recorded.status() != ChannelLedgerResult.InboxStatus.EVENT_RECORDED) {
      throw unavailable();
    }
    target.cancellation().cancel();
    return result(ReliableInboundResult.Status.CONTROL_APPLIED, recorded);
  }

  private ReliableInboundResult recordFeedback(
      ReliableInboundEvent.Accepted event, String decisionCode, DeliveryMessageType messageType) {
    return recordFeedback(
        event.instance(),
        event.externalEventId(),
        event.externalSequence(),
        event.targetId(),
        ChannelFingerprint.request(event.inbound()),
        decisionCode,
        messageType);
  }

  private ReliableInboundResult recordFeedback(
      ReliableInboundEvent.Control event, String decisionCode, DeliveryMessageType messageType) {
    return recordFeedback(
        event.instance(),
        event.externalEventId(),
        event.externalSequence(),
        event.targetId(),
        "",
        decisionCode,
        messageType);
  }

  private ReliableInboundResult recordFeedback(
      ChannelInstanceId instance,
      String externalEventId,
      long externalSequence,
      String targetId,
      String requestFingerprint,
      String decisionCode,
      DeliveryMessageType messageType) {
    Instant recordedAt = clock.instant();
    String eventFingerprint =
        ChannelFingerprint.event(
            instance,
            externalEventId,
            externalSequence,
            InboxEventKind.FEEDBACK,
            decisionCode,
            requestFingerprint);
    DeliveryEnvelope feedback =
        DeliveryEnvelope.create(
            instance,
            "delivery-" + eventFingerprint,
            targetId,
            DeliverySourceKind.CHANNEL_FEEDBACK,
            externalEventId,
            messageType,
            "",
            false,
            settings.chunkAlgorithm(),
            messageType == DeliveryMessageType.SESSION_BUSY
                ? settings.sessionBusyParts()
                : settings.noActiveTurnParts());
    ChannelLedgerResult.Event recorded =
        record(
            new ChannelLedgerCommand.RecordEvent(
                instance,
                externalEventId,
                externalSequence,
                eventFingerprint,
                InboxEventKind.FEEDBACK,
                decisionCode,
                requestFingerprint,
                null,
                feedback,
                recordedAt));
    if (recorded.status() != ChannelLedgerResult.InboxStatus.FEEDBACK_QUEUED) {
      throw unavailable();
    }
    return result(ReliableInboundResult.Status.FEEDBACK_QUEUED, recorded);
  }

  private ChannelLedgerCommand.RecordEvent turnCommand(
      ReliableInboundEvent.Accepted event, InboundMessage inbound) {
    Instant recordedAt = clock.instant();
    String requestFingerprint = ChannelFingerprint.request(inbound);
    String eventFingerprint =
        ChannelFingerprint.event(
            event.instance(),
            event.externalEventId(),
            event.externalSequence(),
            InboxEventKind.TURN,
            "",
            requestFingerprint);
    return new ChannelLedgerCommand.RecordEvent(
        event.instance(),
        event.externalEventId(),
        event.externalSequence(),
        eventFingerprint,
        InboxEventKind.TURN,
        "",
        requestFingerprint,
        new ChannelLedgerCommand.TurnReservation(
            inbound.messageId(), requestFingerprint, inbound.turnId()),
        null,
        recordedAt);
  }

  private void start(ActiveTurn active) {
    try {
      active.thread(
          Objects.requireNonNull(
              starter.start(TURN_WORKER, () -> runTurn(active)), "starter 返回了 null"));
    } catch (Throwable failure) {
      cleanup(active);
      recoverAfterStartFailure(active.event().instance());
      throw new ReliableChannelException(ReliableChannelFailure.TURN_START_FAILED);
    }
  }

  private void runTurn(ActiveTurn active) {
    try {
      Instant startedAt = clock.instant();
      ChannelLedgerResult.TurnStart started =
          startTurn(
              new ChannelLedgerCommand.StartTurn(
                  active.event().instance(),
                  active.event().externalEventId(),
                  active.inbound().messageId(),
                  active.inbound().turnId(),
                  ownerId,
                  active.revision(),
                  startedAt.plus(settings.turnLease()),
                  startedAt));
      if (started.status() == ChannelLedgerResult.TurnStartStatus.STARTED) {
        turns.process(active.inbound(), active.cancellation().token());
      }
    } catch (RuntimeException ignored) {
      // 持久边界失败或 Turn 流程失败均由状态恢复/权威终态处理，原始异常不跨渠道边界。
    } finally {
      cleanup(active);
    }
  }

  private void recoverAfterStartFailure(ChannelInstanceId instance) {
    try {
      ledger.recover(
          new ChannelLedgerCommand.Recover(
              instance, ownerId, clock.instant(), settings.recoveryBatchSize()));
    } catch (RuntimeException failure) {
      throw mapLedgerFailure(failure);
    }
  }

  private ChannelLedgerResult.Event record(ChannelLedgerCommand.RecordEvent command) {
    try {
      return Objects.requireNonNull(ledger.recordEvent(command), "ledger 返回了 null");
    } catch (RuntimeException failure) {
      throw mapLedgerFailure(failure);
    }
  }

  private ChannelLedgerResult.TurnStart startTurn(ChannelLedgerCommand.StartTurn command) {
    try {
      return Objects.requireNonNull(ledger.startTurn(command), "ledger 返回了 null");
    } catch (RuntimeException failure) {
      throw mapLedgerFailure(failure);
    }
  }

  private static ReliableChannelException mapLedgerFailure(RuntimeException failure) {
    if (failure instanceof ReliableChannelException reliable) {
      return reliable;
    }
    if (failure instanceof ChannelLedgerFailureCarrier carrier) {
      return new ReliableChannelException(map(carrier.ledgerFailureKind()));
    }
    return unavailable();
  }

  private static ReliableChannelFailure map(ChannelLedgerFailureKind kind) {
    return switch (Objects.requireNonNull(kind, "kind")) {
      case UNAVAILABLE -> ReliableChannelFailure.LEDGER_UNAVAILABLE;
      case IDEMPOTENCY_CONFLICT -> ReliableChannelFailure.IDEMPOTENCY_CONFLICT;
      case CAPACITY_EXCEEDED -> ReliableChannelFailure.LEDGER_CAPACITY_EXCEEDED;
      case STALE_WRITE -> ReliableChannelFailure.LEDGER_STALE_WRITE;
    };
  }

  private static ReliableChannelException unavailable() {
    return new ReliableChannelException(ReliableChannelFailure.LEDGER_UNAVAILABLE);
  }

  private static ReliableInboundResult result(
      ReliableInboundResult.Status status, ChannelLedgerResult.Event recorded) {
    return new ReliableInboundResult(status, recorded.turnId(), recorded.nextSequence());
  }

  private static InboundMessage withTurnId(InboundMessage inbound, String turnId) {
    return new InboundMessage(
        inbound.schemaVersion(),
        inbound.messageId(),
        turnId,
        inbound.sessionId(),
        inbound.route(),
        inbound.senderId(),
        inbound.content(),
        inbound.occurredAt());
  }

  private void cleanup(ActiveTurn active) {
    if (!active.cleaned().compareAndSet(false, true)) {
      return;
    }
    synchronized (registrations) {
      activeTurns.remove(active.key(), active);
    }
    permits.release();
  }

  int activeTurnCount() {
    return activeTurns.size();
  }

  int availableTurnPermits() {
    return permits.availablePermits();
  }

  boolean cancellationRequested(String sessionId) {
    return activeTurns.entrySet().stream()
        .filter(entry -> entry.getKey().sessionId().equals(sessionId))
        .map(java.util.Map.Entry::getValue)
        .anyMatch(active -> active.cancellation().token().isCancellationRequested());
  }

  private record ActiveKey(ChannelInstanceId instance, String sessionId) {
    private ActiveKey {
      Objects.requireNonNull(instance, "instance");
      Objects.requireNonNull(sessionId, "sessionId");
    }
  }

  private static final class ActiveTurn {
    private final ReliableInboundEvent.Accepted event;
    private final TurnCancellationSource cancellation = new TurnCancellationSource();
    private final AtomicBoolean cleaned = new AtomicBoolean();
    private volatile InboundMessage inbound;
    private volatile long revision;
    private volatile Thread thread;

    private ActiveTurn(ReliableInboundEvent.Accepted event, InboundMessage inbound) {
      this.event = event;
      this.inbound = inbound;
    }

    private void prepare(InboundMessage durableInbound, long durableRevision) {
      inbound = Objects.requireNonNull(durableInbound, "durableInbound");
      revision = durableRevision;
    }

    private void thread(Thread startedThread) {
      thread = Objects.requireNonNull(startedThread, "startedThread");
    }

    private String externalMessageId() {
      return inbound.messageId();
    }

    private ActiveKey key() {
      return new ActiveKey(event.instance(), inbound.sessionId());
    }

    private ReliableInboundEvent.Accepted event() {
      return event;
    }

    private InboundMessage inbound() {
      return inbound;
    }

    private long revision() {
      return revision;
    }

    private TurnCancellationSource cancellation() {
      return cancellation;
    }

    private AtomicBoolean cleaned() {
      return cleaned;
    }

    @Override
    public String toString() {
      return "ActiveTurn[sensitiveFields=<redacted>]";
    }
  }
}
