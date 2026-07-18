package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.application.control.ActiveTurnObserver;
import io.namei.agent.application.control.ActiveTurnRegistry;
import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.MessageContract;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.reliability.ChannelFingerprint;
import io.namei.agent.kernel.channel.reliability.ChannelInstanceId;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerCommand;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerFailureCarrier;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerFailureKind;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerResult;
import io.namei.agent.kernel.channel.reliability.DeliveryMessageType;
import io.namei.agent.kernel.channel.reliability.InboxEventKind;
import io.namei.agent.kernel.control.ControlCancelResult;
import io.namei.agent.kernel.control.ControlTerminalKind;
import io.namei.agent.kernel.port.ChannelLedgerPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReliableInboundCoordinatorTest {
  private static final Instant NOW = Instant.parse("2026-07-16T06:00:00Z");
  private static final ChannelInstanceId INSTANCE = ChannelInstanceId.derive("telegram", "100001");
  private static final ChannelInstanceId OTHER_INSTANCE =
      ChannelInstanceId.derive("telegram", "100002");
  private static final String OWNER = "1".repeat(32);
  private static final ReliableInboundSettings SETTINGS =
      new ReliableInboundSettings(
          2,
          Duration.ofMinutes(5),
          100,
          "telegram-text-chunks-v1",
          List.of("fixed-session-busy"),
          List.of("fixed-no-active-turn"));

  @Test
  void recordsClaimBeforeStartingAndCrossesTurnBoundaryOnlyAfterCommit() {
    var order = new ArrayList<String>();
    var ledger = new FakeLedger(order);
    var starter = new CapturingStarter(order);
    var invocations = new ArrayList<InboundMessage>();
    var contexts = new ArrayList<ReliableTurnContext>();
    var coordinator =
        coordinator(
            ledger,
            starter,
            (context, ignored) -> {
              order.add("turn");
              contexts.add(context);
              invocations.add(context.inbound());
            });

    ReliableInboundResult result = coordinator.handle(accepted("update-0", 0, "message-0", "s-0"));

    assertThat(result.status()).isEqualTo(ReliableInboundResult.Status.TURN_SCHEDULED);
    assertThat(order).containsExactly("record-TURN", "thread-start");
    assertThat(invocations).isEmpty();
    assertThat(ledger.events.getFirst().reservation().turnId()).isEqualTo("turn-generated-1");

    starter.runNext();

    assertThat(order).containsExactly("record-TURN", "thread-start", "start-turn", "turn");
    assertThat(invocations).extracting(InboundMessage::turnId).containsExactly("turn-generated-1");
    assertThat(contexts.getFirst().instance()).isEqualTo(INSTANCE);
    assertThat(contexts.getFirst().targetId()).isEqualTo("10001");
    assertThat(contexts.getFirst().claimRevision()).isOne();
    assertThat(ledger.starts.getFirst().expectedRevision()).isZero();
    assertThat(coordinator.activeTurnCount()).isZero();
    assertThat(coordinator.availableTurnPermits()).isEqualTo(2);
  }

  @Test
  void staleStartAndDurableDuplicateStatesNeverInvokeTurn() {
    var staleLedger = new FakeLedger(new ArrayList<>());
    staleLedger.startResult =
        new ChannelLedgerResult.TurnStart(ChannelLedgerResult.TurnStartStatus.STALE, 1, 0);
    var staleStarter = new CapturingStarter(new ArrayList<>());
    var staleInvocations = new AtomicInteger();
    var stale =
        coordinator(
            staleLedger,
            staleStarter,
            (ignoredInbound, ignoredCancellation) -> staleInvocations.incrementAndGet());

    stale.handle(accepted("update-stale", 0, "message-stale", "s-stale"));
    staleStarter.runNext();

    assertThat(staleInvocations).hasValue(0);
    assertThat(stale.activeTurnCount()).isZero();
    assertThat(stale.availableTurnPermits()).isEqualTo(2);

    var cases =
        List.of(
            new DuplicateCase(
                ChannelLedgerResult.InboxStatus.IN_PROGRESS,
                ReliableInboundResult.Status.IN_PROGRESS),
            new DuplicateCase(
                ChannelLedgerResult.InboxStatus.ALREADY_TERMINAL,
                ReliableInboundResult.Status.ALREADY_TERMINAL),
            new DuplicateCase(
                ChannelLedgerResult.InboxStatus.EXECUTION_UNKNOWN,
                ReliableInboundResult.Status.EXECUTION_UNKNOWN));
    for (DuplicateCase duplicate : cases) {
      var ledger = new FakeLedger(new ArrayList<>());
      ledger.eventResults.add(
          new ChannelLedgerResult.Event(duplicate.ledger(), "turn-durable", 3, null, 8));
      var starter = new CapturingStarter(new ArrayList<>());
      var invocations = new AtomicInteger();
      var coordinator =
          coordinator(
              ledger,
              starter,
              (ignoredInbound, ignoredCancellation) -> invocations.incrementAndGet());

      ReliableInboundResult result =
          coordinator.handle(accepted("update-" + duplicate.ledger(), 7, "message-1", "s-1"));

      assertThat(result.status()).isEqualTo(duplicate.application());
      assertThat(starter.calls).isZero();
      assertThat(invocations).hasValue(0);
      assertThat(coordinator.activeTurnCount()).isZero();
      assertThat(coordinator.availableTurnPermits()).isEqualTo(2);
    }
  }

  @Test
  void starterFailureReleasesResourcesAndRetryUsesOriginalTurnId() {
    var ledger = new FakeLedger(new ArrayList<>());
    ledger.eventResults.add(
        new ChannelLedgerResult.Event(
            ChannelLedgerResult.InboxStatus.RESERVED_NEW, "turn-original", 0, null, 0));
    ledger.eventResults.add(
        new ChannelLedgerResult.Event(
            ChannelLedgerResult.InboxStatus.START_RETRYABLE, "turn-original", 1, null, 0));
    var starter = new CapturingStarter(new ArrayList<>());
    starter.failNext = true;
    var turns = new ArrayList<InboundMessage>();
    var coordinator =
        coordinator(ledger, starter, (context, ignored) -> turns.add(context.inbound()));
    ReliableInboundEvent.Accepted event = accepted("update-0", 0, "message-0", "s-0");

    assertThatThrownBy(() -> coordinator.handle(event))
        .isInstanceOf(ReliableChannelException.class)
        .extracting(failure -> ((ReliableChannelException) failure).failure())
        .isEqualTo(ReliableChannelFailure.TURN_START_FAILED);
    assertThat(ledger.recoveries).hasSize(1);
    assertThat(coordinator.activeTurnCount()).isZero();
    assertThat(coordinator.availableTurnPermits()).isEqualTo(2);

    assertThat(coordinator.handle(event).status())
        .isEqualTo(ReliableInboundResult.Status.TURN_SCHEDULED);
    starter.runNext();

    assertThat(ledger.events)
        .extracting(command -> command.reservation().turnId())
        .containsExactly("turn-generated-1", "turn-generated-2");
    assertThat(ledger.starts)
        .extracting(ChannelLedgerCommand.StartTurn::turnId)
        .containsExactly("turn-original");
    assertThat(turns).extracting(InboundMessage::turnId).containsExactly("turn-original");
    assertThat(coordinator.activeTurnCount()).isZero();
    assertThat(coordinator.availableTurnPermits()).isEqualTo(2);
  }

  @Test
  void busyIgnoredAndNoActiveControlPersistOnlyFixedSafeCommands() {
    var ledger = new FakeLedger(new ArrayList<>());
    var starter = new CapturingStarter(new ArrayList<>());
    var coordinator =
        coordinator(
            ledger,
            starter,
            (ignoredInbound, ignoredCancellation) -> {},
            new ReliableInboundSettings(
                1,
                Duration.ofMinutes(5),
                100,
                "telegram-text-chunks-v1",
                List.of("fixed-session-busy"),
                List.of("fixed-no-active-turn")));
    coordinator.handle(accepted("update-active", 0, "message-active", "s-active"));
    ReliableInboundEvent.Accepted secret =
        accepted("update-secret", 1, "message-secret", "s-secret", "private-user-body");

    ReliableInboundResult busy = coordinator.handle(secret);
    ReliableInboundResult ignored =
        coordinator.handle(
            ReliableInboundEvent.ignored(INSTANCE, "update-ignore", 2, "NOT_ALLOWED"));
    ReliableInboundResult noActive =
        coordinator.handle(
            ReliableInboundEvent.control(
                INSTANCE, "update-cancel", 3, "CANCEL", "10001", "missing-session"));

    assertThat(busy.status()).isEqualTo(ReliableInboundResult.Status.FEEDBACK_QUEUED);
    assertThat(ignored.status()).isEqualTo(ReliableInboundResult.Status.IGNORED_RECORDED);
    assertThat(noActive.status()).isEqualTo(ReliableInboundResult.Status.FEEDBACK_QUEUED);
    ChannelLedgerCommand.RecordEvent busyCommand = event(ledger, "update-secret");
    assertThat(busyCommand.kind()).isEqualTo(InboxEventKind.FEEDBACK);
    assertThat(busyCommand.decisionCode()).isEqualTo("SESSION_BUSY");
    assertThat(busyCommand.requestFingerprint())
        .isEqualTo(ChannelFingerprint.request(secret.inbound()));
    assertThat(busyCommand.feedback().messageType()).isEqualTo(DeliveryMessageType.SESSION_BUSY);
    assertThat(busyCommand.feedback().parts())
        .extracting(part -> part.payload())
        .containsExactly("fixed-session-busy")
        .noneMatch(payload -> payload.contains("private-user-body"));
    ChannelLedgerCommand.RecordEvent ignoredCommand = event(ledger, "update-ignore");
    assertThat(ignoredCommand.kind()).isEqualTo(InboxEventKind.IGNORED);
    assertThat(ignoredCommand.requestFingerprint()).isEmpty();
    assertThat(ignoredCommand.feedback()).isNull();
    ChannelLedgerCommand.RecordEvent noActiveCommand = event(ledger, "update-cancel");
    assertThat(noActiveCommand.kind()).isEqualTo(InboxEventKind.FEEDBACK);
    assertThat(noActiveCommand.decisionCode()).isEqualTo("NO_ACTIVE_TURN");
    assertThat(noActiveCommand.feedback().messageType())
        .isEqualTo(DeliveryMessageType.NO_ACTIVE_TURN);
    assertThat(noActiveCommand.feedback().parts())
        .extracting(part -> part.payload())
        .containsExactly("fixed-no-active-turn");

    starter.runNext();
  }

  @Test
  void controlCommitsBeforeCancellingOnlyItsTargetTurn() throws Exception {
    var ledger = new FakeLedger(new ArrayList<>());
    var starter = new CapturingStarter(new ArrayList<>());
    var observed = new ConcurrentHashMap<String, Boolean>();
    var cancellationAfterCommit = new AtomicBoolean();
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    ReliableTurnProcessor processor =
        (context, cancellation) -> {
          InboundMessage inbound = context.inbound();
          cancellation.onCancellation(
              () -> cancellationAfterCommit.set(ledger.controlCommitReturned.get()));
          observed.put(inbound.sessionId(), cancellation.isCancellationRequested());
          entered.countDown();
          await(release);
          observed.put(inbound.sessionId(), cancellation.isCancellationRequested());
        };
    var coordinator = coordinator(ledger, starter, processor);
    coordinator.handle(accepted("update-a", 0, "message-a", "session-a"));
    coordinator.handle(accepted("update-b", 1, "message-b", "session-b"));

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(starter.removeNext());
      var second = executor.submit(starter.removeNext());
      entered.await();
      try {
        ReliableInboundResult isolated =
            coordinator.handle(
                ReliableInboundEvent.control(
                    OTHER_INSTANCE, "update-other-instance", 0, "CANCEL", "10001", "session-a"));
        assertThat(isolated.status()).isEqualTo(ReliableInboundResult.Status.FEEDBACK_QUEUED);
        assertThat(coordinator.cancellationRequested("session-a")).isFalse();

        ReliableInboundResult controlled =
            coordinator.handle(
                ReliableInboundEvent.control(
                    INSTANCE, "update-cancel", 2, "CANCEL", "10001", "session-a"));

        assertThat(controlled.status()).isEqualTo(ReliableInboundResult.Status.CONTROL_APPLIED);
        assertThat(cancellationAfterCommit).isTrue();
        assertThat(coordinator.cancellationRequested("session-a")).isTrue();
        assertThat(coordinator.cancellationRequested("session-b")).isFalse();
        ChannelLedgerCommand.RecordEvent control = event(ledger, "update-cancel");
        assertThat(control.kind()).isEqualTo(InboxEventKind.CONTROL);
        assertThat(control.requestFingerprint()).isEmpty();
        assertThat(control.feedback()).isNull();
      } finally {
        release.countDown();
      }
      first.get();
      second.get();
    }
    assertThat(observed).containsEntry("session-a", true).containsEntry("session-b", false);
  }

  @Test
  void registersControlOnlyAfterClaimAndReusesTheReliableCancellationSource() throws Exception {
    var ledger = new FakeLedger(new ArrayList<>());
    var starter = new CapturingStarter(new ArrayList<>());
    var registeredAfterClaim = new AtomicBoolean();
    var entered = new CountDownLatch(1);
    var released = new CountDownLatch(1);
    var allowCompletion = new CountDownLatch(1);
    var reference = controlRef(7);
    var registry =
        new ActiveTurnRegistry(
            Clock.fixed(NOW, ZoneOffset.UTC), () -> reference, 1, Duration.ofMinutes(5), 1);
    ActiveTurnObserver observer =
        (channel, cancellation, startedAt) -> {
          registeredAfterClaim.set(!ledger.starts.isEmpty());
          return registry.register(channel, cancellation, startedAt);
        };
    ReliableTurnProcessor processor =
        (context, cancellation) -> {
          assertThat(context.controlRegistration().registered()).isTrue();
          try (var ignored = cancellation.onCancellation(released::countDown)) {
            entered.countDown();
            await(released);
            await(allowCompletion);
          }
        };
    var coordinator = coordinator(ledger, starter, processor, SETTINGS, observer);
    coordinator.handle(accepted("update-control", 0, "message-control", "session-control"));

    Thread worker = Thread.ofVirtual().start(starter.removeNext());
    entered.await();
    int ledgerEventsBeforeCancel = ledger.events.size();

    try {
      assertThat(registeredAfterClaim).isTrue();
      assertThat(registry.cancel(reference).result())
          .isEqualTo(ControlCancelResult.CANCELLATION_REQUESTED);
    } finally {
      allowCompletion.countDown();
    }
    worker.join();

    assertThat(ledger.events).hasSize(ledgerEventsBeforeCancel);
    assertThat(registry.terminalKind(reference)).contains(ControlTerminalKind.SOURCE_ENDED);
    assertThat(coordinator.activeTurnCount()).isZero();
  }

  @Test
  void genericReliableCoordinatorUsesTheInstanceChannelForControlObservation() {
    var ledger = new FakeLedger(new ArrayList<>());
    var starter = new CapturingStarter(new ArrayList<>());
    var observedChannel = new AtomicReference<String>();
    ActiveTurnObserver observer =
        (channel, cancellation, startedAt) -> {
          observedChannel.set(channel);
          return io.namei.agent.application.control.ActiveTurnRegistration.disabled();
        };
    var coordinator =
        coordinator(ledger, starter, (context, cancellation) -> {}, SETTINGS, observer);
    ChannelInstanceId matrixInstance = ChannelInstanceId.derive("matrix", "instance-test");
    InboundMessage inbound =
        new InboundMessage(
            MessageContract.CURRENT_VERSION,
            "message-matrix",
            "mapper-turn",
            "matrix:room",
            new MessageRoute("matrix", "room"),
            "sender",
            "question",
            NOW);

    coordinator.handle(
        ReliableInboundEvent.accepted(matrixInstance, "event-matrix", 0, "room", inbound));
    starter.runNext();

    assertThat(observedChannel).hasValue("matrix");
  }

  @Test
  void observerFailureAndExecutionUnknownNeverChangeReliableProcessing() {
    var healthyLedger = new FakeLedger(new ArrayList<>());
    var healthyStarter = new CapturingStarter(new ArrayList<>());
    var processorCalls = new AtomicInteger();
    ActiveTurnObserver failingObserver =
        (channel, cancellation, startedAt) -> {
          throw new IllegalStateException("observer-secret");
        };
    var healthy =
        coordinator(
            healthyLedger,
            healthyStarter,
            (context, cancellation) -> processorCalls.incrementAndGet(),
            SETTINGS,
            failingObserver);

    healthy.handle(accepted("update-observer", 0, "message-observer", "session-observer"));
    healthyStarter.runNext();

    assertThat(processorCalls).hasValue(1);
    assertThat(healthy.activeTurnCount()).isZero();

    var unknownLedger = new FakeLedger(new ArrayList<>());
    unknownLedger.eventResults.add(
        new ChannelLedgerResult.Event(
            ChannelLedgerResult.InboxStatus.EXECUTION_UNKNOWN, "turn-unknown", 3, null, 1));
    var unknownStarter = new CapturingStarter(new ArrayList<>());
    var observerCalls = new AtomicInteger();
    ActiveTurnObserver recordingObserver =
        (channel, cancellation, startedAt) -> {
          observerCalls.incrementAndGet();
          return io.namei.agent.application.control.ActiveTurnRegistration.disabled();
        };
    var unknown =
        coordinator(
            unknownLedger,
            unknownStarter,
            (context, cancellation) -> {},
            SETTINGS,
            recordingObserver);

    assertThat(
            unknown
                .handle(accepted("update-unknown", 0, "message-unknown", "session-unknown"))
                .status())
        .isEqualTo(ReliableInboundResult.Status.EXECUTION_UNKNOWN);
    assertThat(observerCalls).hasValue(0);
    assertThat(unknownStarter.calls).isZero();
  }

  @Test
  void mapsLedgerConflictCapacityAndUnavailableToStableChannelFailures() {
    var cases =
        Map.of(
            ChannelLedgerFailureKind.IDEMPOTENCY_CONFLICT,
            ReliableChannelFailure.IDEMPOTENCY_CONFLICT,
            ChannelLedgerFailureKind.CAPACITY_EXCEEDED,
            ReliableChannelFailure.LEDGER_CAPACITY_EXCEEDED,
            ChannelLedgerFailureKind.UNAVAILABLE,
            ReliableChannelFailure.LEDGER_UNAVAILABLE);
    for (Map.Entry<ChannelLedgerFailureKind, ReliableChannelFailure> entry : cases.entrySet()) {
      var ledger = new FakeLedger(new ArrayList<>());
      ledger.recordFailure = new ClassifiedLedgerException(entry.getKey());
      var coordinator =
          coordinator(
              ledger,
              new CapturingStarter(new ArrayList<>()),
              (ignoredInbound, ignoredCancellation) -> {});

      assertThatThrownBy(
              () -> coordinator.handle(accepted("update-" + entry.getKey(), 0, "message", "s")))
          .isInstanceOf(ReliableChannelException.class)
          .hasMessageNotContaining("update-")
          .extracting(failure -> ((ReliableChannelException) failure).failure())
          .isEqualTo(entry.getValue());
    }

    var ledger = new FakeLedger(new ArrayList<>());
    ledger.recordFailure = new IllegalStateException("sensitive-database-detail");
    var coordinator =
        coordinator(
            ledger,
            new CapturingStarter(new ArrayList<>()),
            (ignoredInbound, ignoredCancellation) -> {});
    assertThatThrownBy(() -> coordinator.handle(accepted("update-x", 0, "message", "s")))
        .isInstanceOf(ReliableChannelException.class)
        .hasMessageNotContaining("sensitive-database-detail")
        .extracting(failure -> ((ReliableChannelException) failure).failure())
        .isEqualTo(ReliableChannelFailure.LEDGER_UNAVAILABLE);
  }

  @Test
  void shutdownCancelsAndReleasesATurnThatHasNotCrossedTheExecutionBoundary() {
    var ledger = new FakeLedger(new ArrayList<>());
    var starter = new CapturingStarter(new ArrayList<>());
    var calls = new AtomicInteger();
    var coordinator =
        coordinator(ledger, starter, (ignored, cancellation) -> calls.incrementAndGet());

    coordinator.handle(accepted("update-close", 0, "message-close", "session-close"));

    assertThat(coordinator.shutdown(Duration.ofMillis(50))).isTrue();
    assertThat(coordinator.activeTurnCount()).isZero();
    assertThat(coordinator.availableTurnPermits()).isEqualTo(2);
    starter.runNext();
    assertThat(calls).hasValue(0);
    assertThat(ledger.starts).isEmpty();
    assertThatThrownBy(
            () ->
                coordinator.handle(
                    accepted("update-after-close", 1, "message-after-close", "session-close")))
        .isInstanceOf(IllegalStateException.class);
  }

  private static ReliableInboundCoordinator coordinator(
      FakeLedger ledger, CapturingStarter starter, ReliableTurnProcessor processor) {
    return coordinator(ledger, starter, processor, SETTINGS);
  }

  private static ReliableInboundCoordinator coordinator(
      FakeLedger ledger,
      CapturingStarter starter,
      ReliableTurnProcessor processor,
      ReliableInboundSettings settings) {
    var generated = new AtomicInteger();
    return new ReliableInboundCoordinator(
        ledger,
        processor,
        starter,
        Clock.fixed(NOW, ZoneOffset.UTC),
        () -> OWNER,
        () -> "turn-generated-" + generated.incrementAndGet(),
        settings);
  }

  private static ReliableInboundCoordinator coordinator(
      FakeLedger ledger,
      CapturingStarter starter,
      ReliableTurnProcessor processor,
      ReliableInboundSettings settings,
      ActiveTurnObserver observer) {
    var generated = new AtomicInteger();
    return new ReliableInboundCoordinator(
        ledger,
        processor,
        starter,
        Clock.fixed(NOW, ZoneOffset.UTC),
        () -> OWNER,
        () -> "turn-generated-" + generated.incrementAndGet(),
        settings,
        observer);
  }

  private static ReliableInboundEvent.Accepted accepted(
      String eventId, long sequence, String messageId, String sessionId) {
    return accepted(eventId, sequence, messageId, sessionId, "question");
  }

  private static io.namei.agent.kernel.control.ControlTurnRef controlRef(int lastByte) {
    byte[] bytes = new byte[16];
    bytes[15] = (byte) lastByte;
    return io.namei.agent.kernel.control.ControlTurnRef.fromBytes(bytes);
  }

  private static ReliableInboundEvent.Accepted accepted(
      String eventId, long sequence, String messageId, String sessionId, String content) {
    return ReliableInboundEvent.accepted(
        INSTANCE,
        eventId,
        sequence,
        "10001",
        new InboundMessage(
            MessageContract.CURRENT_VERSION,
            messageId,
            "mapper-turn",
            sessionId,
            new MessageRoute("telegram", "10001"),
            "10001",
            content,
            NOW));
  }

  private static ChannelLedgerCommand.RecordEvent event(FakeLedger ledger, String eventId) {
    return ledger.events.stream()
        .filter(command -> command.externalEventId().equals(eventId))
        .findFirst()
        .orElseThrow();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private record DuplicateCase(
      ChannelLedgerResult.InboxStatus ledger, ReliableInboundResult.Status application) {}

  private static final class CapturingStarter implements ReliableTurnStarter {
    private final List<String> order;
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private int calls;
    private boolean failNext;

    private CapturingStarter(List<String> order) {
      this.order = order;
    }

    @Override
    public Thread start(String name, Runnable task) {
      calls++;
      order.add("thread-start");
      if (failNext) {
        failNext = false;
        throw new IllegalStateException("sensitive-starter-failure");
      }
      tasks.addLast(task);
      return Thread.ofPlatform().name(name).unstarted(task);
    }

    private Runnable removeNext() {
      return tasks.removeFirst();
    }

    private void runNext() {
      removeNext().run();
    }
  }

  private static final class ClassifiedLedgerException extends RuntimeException
      implements ChannelLedgerFailureCarrier {
    private final ChannelLedgerFailureKind kind;

    private ClassifiedLedgerException(ChannelLedgerFailureKind kind) {
      super("sensitive-ledger-detail");
      this.kind = kind;
    }

    @Override
    public ChannelLedgerFailureKind ledgerFailureKind() {
      return kind;
    }
  }

  private static final class FakeLedger implements ChannelLedgerPort {
    private final List<String> order;
    private final List<ChannelLedgerCommand.RecordEvent> events = new ArrayList<>();
    private final List<ChannelLedgerCommand.StartTurn> starts = new ArrayList<>();
    private final List<ChannelLedgerCommand.Recover> recoveries = new ArrayList<>();
    private final ArrayDeque<ChannelLedgerResult.Event> eventResults = new ArrayDeque<>();
    private final AtomicBoolean controlCommitReturned = new AtomicBoolean();
    private RuntimeException recordFailure;
    private ChannelLedgerResult.TurnStart startResult =
        new ChannelLedgerResult.TurnStart(ChannelLedgerResult.TurnStartStatus.STARTED, 1, 1);

    private FakeLedger(List<String> order) {
      this.order = order;
    }

    @Override
    public ChannelLedgerResult.Event recordEvent(ChannelLedgerCommand.RecordEvent command) {
      events.add(command);
      order.add("record-" + command.kind());
      if (recordFailure != null) {
        throw recordFailure;
      }
      ChannelLedgerResult.Event result =
          eventResults.isEmpty() ? defaultResult(command) : eventResults.removeFirst();
      if (command.kind() == InboxEventKind.CONTROL) {
        controlCommitReturned.set(true);
      }
      return result;
    }

    private static ChannelLedgerResult.Event defaultResult(
        ChannelLedgerCommand.RecordEvent command) {
      return switch (command.kind()) {
        case TURN ->
            new ChannelLedgerResult.Event(
                ChannelLedgerResult.InboxStatus.RESERVED_NEW,
                command.reservation().turnId(),
                0,
                null,
                command.externalSequence());
        case FEEDBACK ->
            new ChannelLedgerResult.Event(
                ChannelLedgerResult.InboxStatus.FEEDBACK_QUEUED,
                null,
                0,
                command.feedback().deliveryId(),
                command.externalSequence() + 1);
        case IGNORED, CONTROL ->
            new ChannelLedgerResult.Event(
                ChannelLedgerResult.InboxStatus.EVENT_RECORDED,
                null,
                0,
                null,
                command.externalSequence() + 1);
      };
    }

    @Override
    public ChannelLedgerResult.TurnStart startTurn(ChannelLedgerCommand.StartTurn command) {
      starts.add(command);
      order.add("start-turn");
      return startResult;
    }

    @Override
    public ChannelLedgerResult.Recovery recover(ChannelLedgerCommand.Recover command) {
      recoveries.add(command);
      return new ChannelLedgerResult.Recovery(1, false);
    }

    @Override
    public ChannelLedgerResult.Terminal recordTerminal(
        ChannelLedgerCommand.RecordTerminal command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<ChannelLedgerResult.DeliveryWork> claimNextDelivery(
        ChannelLedgerCommand.ClaimDelivery command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChannelLedgerResult.DeliveryUpdate recordDeliveryOutcome(
        ChannelLedgerCommand.RecordDeliveryOutcome command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChannelLedgerResult.Cleanup cleanup(ChannelLedgerCommand.Cleanup command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChannelLedgerResult.Snapshot snapshot(ChannelInstanceId instance) {
      throw new UnsupportedOperationException();
    }
  }
}
