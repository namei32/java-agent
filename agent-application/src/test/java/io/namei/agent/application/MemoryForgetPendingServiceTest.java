package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PendingTurnAnchor;
import io.namei.agent.kernel.model.PendingTurnResolution;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import io.namei.agent.kernel.port.SessionRepository;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MemoryForgetPendingServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-19T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void shortCircuitsAnEmptyNormalizedRequestBeforeGeneratingOrWritingAnything() {
    var store = new RecordingStore();
    var sessions = new RecordingSessions();
    var service = service(store, sessions);

    MemoryForgetPendingOutcome outcome =
        service.create(
            request(new ToolCall("call-1", "forget_memory", Map.of("ids", List.of(" ", "")))));

    assertThat(outcome).isInstanceOf(MemoryForgetPendingOutcome.ImmediateSuccess.class);
    assertThat(((MemoryForgetPendingOutcome.ImmediateSuccess) outcome).safeResult().content())
        .isEqualTo("{\"requested_ids\":[],\"superseded_ids\":[],\"missing_ids\":[],\"count\":0}");
    assertThat(store.events).isEmpty();
    assertThat(sessions.events).isEmpty();
  }

  @Test
  void createsTheBoundOperationBeforeTheExactPendingTurnAnchor() {
    var store = new RecordingStore();
    var sessions = new RecordingSessions();
    var service = service(store, sessions);

    MemoryForgetPendingOutcome outcome =
        service.create(
            request(
                new ToolCall(
                    "call-1",
                    "forget_memory",
                    Map.of("ids", List.of(" memory-a ", "", "memory-b", "memory-a")))));

    assertThat(outcome).isInstanceOf(MemoryForgetPendingOutcome.Pending.class);
    assertThat(store.events).containsExactly("create");
    assertThat(sessions.events).containsExactly("append-pending");
    assertThat(store.createdOperation)
        .satisfies(
            operation -> {
              assertThat(operation.approval().toolName()).isEqualTo("forget_memory");
              assertThat(operation.approval().toolVersion()).isEqualTo("java-memory-forget-v1");
              assertThat(operation.expectedNextSequence()).isEqualTo(2);
            });
    assertThat(store.createdCapsule.canonicalArgumentsJson())
        .isEqualTo("{\"ids\":[\"memory-a\",\"memory-b\"]}");
    assertThat(sessions.anchor)
        .satisfies(
            anchor -> {
              assertThat(anchor.operationReference())
                  .isEqualTo(store.createdOperation.reference().value());
              assertThat(anchor.resumeNextSequence()).isEqualTo(2);
              assertThat(anchor.projectionVersion())
                  .isEqualTo(MemoryForgetCapability.PROJECTION_VERSION);
            });
  }

  @Test
  void stalesTheUnconsumedOperationWhenTheSessionCasDoesNotWin() {
    var store = new RecordingStore();
    var sessions = new RecordingSessions();
    sessions.appendResult = false;
    var service = service(store, sessions);

    MemoryForgetPendingOutcome outcome = service.create(request(nonEmptyCall()));

    assertThat(outcome).isInstanceOf(MemoryForgetPendingOutcome.StaleSession.class);
    assertThat(store.events).containsExactly("create", "stale");
    assertThat(sessions.events).containsExactly("append-pending");
    assertThat(store.staleReference).isEqualTo(store.createdOperation.reference());
  }

  @Test
  void stalesThenRethrowsWhenTheSessionWriteFails() {
    var store = new RecordingStore();
    var sessions = new RecordingSessions();
    sessions.failure = new IllegalStateException("session storage unavailable");
    var service = service(store, sessions);

    assertThatThrownBy(() -> service.create(request(nonEmptyCall()))).isSameAs(sessions.failure);

    assertThat(store.events).containsExactly("create", "stale");
  }

  @Test
  void neverTouchesTheSessionWhenOperationCreationFails() {
    var store = new RecordingStore();
    store.failure = new IllegalStateException("operation storage unavailable");
    var sessions = new RecordingSessions();
    var service = service(store, sessions);

    assertThatThrownBy(() -> service.create(request(nonEmptyCall()))).isSameAs(store.failure);

    assertThat(store.events).containsExactly("create");
    assertThat(sessions.events).isEmpty();
  }

  @Test
  void producerBuildsTheFixedPendingProjectionBeforeDelegatingToTheBoundService() {
    var store = new RecordingStore();
    var sessions = new RecordingSessions();
    var producer = MemoryForgetPendingToolset.enabled(service(store, sessions)).producer();

    MemoryForgetPendingOutcome outcome = producer.create(context(), nonEmptyCall());

    assertThat(outcome).isInstanceOf(MemoryForgetPendingOutcome.Pending.class);
    assertThat(store.events).containsExactly("create");
    assertThat(sessions.events).containsExactly("append-pending");
    assertThat(sessions.pendingTurn.assistant().content())
        .isEqualTo(MemoryForgetPendingToolset.pendingAssistantProjection());
  }

  private static MemoryForgetPendingService service(
      RecordingStore store, RecordingSessions sessions) {
    return new MemoryForgetPendingService(
        store,
        sessions,
        () -> PendingOperationReference.of("AAAAAAAAAAAAAAAAAAAAAA"),
        () -> ApprovalInboxReference.of("AQEBAQEBAQEBAQEBAQEBAQ"),
        new FixedIds(),
        CLOCK,
        Duration.ofMinutes(5));
  }

  private static MemoryForgetPendingRequest request(ToolCall call) {
    OffsetDateTime at = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    return new MemoryForgetPendingRequest(
        "session-java-memory-001",
        0,
        "turn-1",
        call,
        new PersistedTurn(
            new ChatMessage(MessageRole.USER, "请遗忘记忆"),
            at,
            new ChatMessage(MessageRole.ASSISTANT, "该操作等待审批。"),
            at.plusSeconds(1)));
  }

  private static ToolCall nonEmptyCall() {
    return new ToolCall("call-1", "forget_memory", Map.of("ids", List.of("memory-a")));
  }

  private static MemoryForgetPendingTurnContext context() {
    OffsetDateTime at = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    return new MemoryForgetPendingTurnContext(
        "session-java-memory-001",
        0,
        "turn-1",
        new ChatMessage(MessageRole.USER, "请遗忘记忆"),
        at,
        CLOCK);
  }

  private static final class FixedIds implements IdGenerator {
    @Override
    public String newTurnId() {
      return "unused-turn-id";
    }

    @Override
    public String newApprovalId() {
      return "approval-id";
    }

    @Override
    public String newIdempotencyKey() {
      return "idempotency-key";
    }
  }

  private static final class RecordingStore implements PendingOperationStore {
    private final List<String> events = new ArrayList<>();
    private RuntimeException failure;
    private PendingOperation createdOperation;
    private PendingOperationCapsule createdCapsule;
    private PendingOperationReference staleReference;

    @Override
    public PendingOperation create(
        PendingOperation operation, ApprovalInboxEntry approval, PendingOperationCapsule capsule) {
      events.add("create");
      if (failure != null) {
        throw failure;
      }
      createdOperation = operation;
      createdCapsule = capsule;
      return operation;
    }

    @Override
    public Optional<PendingOperation> find(PendingOperationReference reference) {
      return Optional.ofNullable(createdOperation)
          .filter(value -> value.reference().equals(reference));
    }

    @Override
    public Optional<PendingOperationCapsule> loadVerifiedCapsule(
        PendingOperationReference reference) {
      return Optional.ofNullable(createdCapsule);
    }

    @Override
    public boolean markStaleSessionIfPending(
        PendingOperationReference reference, Instant observedAt) {
      events.add("stale");
      staleReference = reference;
      return true;
    }

    @Override
    public PendingOperationReservation reserveApproved(
        PendingOperationReference reference, Instant observedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PendingOperationLedgerEntry markRunning(
        PendingOperationReference reference, Instant observedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PendingOperationLedgerEntry markSucceeded(
        PendingOperationReference reference, ToolResult safeResult, Instant observedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PendingOperationLedgerEntry markFailedBeforeStart(
        PendingOperationReference reference, ToolResult safeResult, Instant observedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PendingOperationLedgerEntry markUnknown(
        PendingOperationReference reference, String errorCode, Instant observedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PendingOperation markCommitUnreported(
        PendingOperationReference reference, Instant observedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<PendingOperationLedgerEntry> findLedger(PendingOperationReference reference) {
      return Optional.empty();
    }
  }

  private static final class RecordingSessions implements SessionRepository {
    private final List<String> events = new ArrayList<>();
    private boolean appendResult = true;
    private RuntimeException failure;
    private PendingTurnAnchor anchor;
    private PersistedTurn pendingTurn;

    @Override
    public SessionSnapshot load(String sessionId) {
      return new SessionSnapshot(sessionId, List.of(), 0);
    }

    @Override
    public void appendTurn(String sessionId, PersistedTurn turn) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean appendPendingTurnIfNextSequence(
        PersistedTurn pendingTurn, PendingTurnAnchor value) {
      events.add("append-pending");
      if (failure != null) {
        throw failure;
      }
      anchor = value;
      this.pendingTurn = pendingTurn;
      return appendResult;
    }

    @Override
    public Optional<PendingTurnAnchor> findPendingTurnAnchor(String operationReference) {
      return Optional.ofNullable(anchor);
    }

    @Override
    public boolean appendPendingResolutionIfAnchorMatches(
        PendingTurnAnchor anchor, PendingTurnResolution resolution) {
      throw new UnsupportedOperationException();
    }
  }
}
