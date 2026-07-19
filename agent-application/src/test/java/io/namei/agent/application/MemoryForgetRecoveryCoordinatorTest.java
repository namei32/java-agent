package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.memory.MemoryForgetCommand;
import io.namei.agent.kernel.memory.MemoryForgetResult;
import io.namei.agent.kernel.model.PendingTurnAnchor;
import io.namei.agent.kernel.model.PendingTurnResolution;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import io.namei.agent.kernel.port.MemorySoftForgetPort;
import io.namei.agent.kernel.port.SessionRepository;
import io.namei.agent.kernel.tool.SideEffectExecutionState;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MemoryForgetRecoveryCoordinatorTest {
  private static final Instant NOW = Instant.parse("2026-07-19T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String OPERATION_REF = "AAAAAAAAAAAAAAAAAAAAAA";

  @Test
  void usesTheStaticBoundCapabilityOnlyAfterReservationThenCommitsASafeProjection() {
    Scenario scenario = Scenario.approved();

    assertThat(scenario.coordinator().resume(PendingOperationReference.of(OPERATION_REF)))
        .isEqualTo(MemoryForgetRecoveryCoordinator.Outcome.COMMITTED);

    assertThat(scenario.port().commands)
        .singleElement()
        .satisfies(
            command -> {
              assertThat(command.scope().binding())
                  .isEqualTo("971db4818fc2a938f4d66a981618ddd5e5c2e094d28e1a4eb669ac7c863cc02e");
              assertThat(command.operationKey()).isEqualTo(OPERATION_REF);
              assertThat(command.requestedIds()).containsExactly("memory-a", "memory-b");
            });
    assertThat(scenario.store().events).containsExactly("reserve", "running", "succeeded");
    assertThat(scenario.sessions().resolution)
        .hasValueSatisfying(
            resolution -> {
              assertThat(resolution.projectionVersion())
                  .isEqualTo(MemoryForgetCapability.PROJECTION_VERSION);
              assertThat(resolution.safeAssistantProjection().content())
                  .isEqualTo(MemoryForgetCapability.SAFE_COMPLETION_PROJECTION);
            });
    assertThat(scenario.store().ledger)
        .hasValueSatisfying(
            ledger -> {
              assertThat(ledger.state()).isEqualTo(SideEffectExecutionState.SUCCEEDED);
              assertThat(ledger.safeResult())
                  .hasValueSatisfying(
                      result ->
                          assertThat(result.content())
                              .isEqualTo(
                                  "{\"requested_ids\":[\"memory-a\",\"memory-b\"],"
                                      + "\"superseded_ids\":[\"memory-a\"],"
                                      + "\"missing_ids\":[\"memory-b\"],\"count\":1}"));
            });
    assertThat(MemoryForgetCapability.definition().name()).isEqualTo("forget_memory");
    assertThat(MemoryForgetCapability.definition().version()).isEqualTo("java-memory-forget-v1");
    assertThat(MemoryForgetCapability.definition().risk()).isEqualTo(ToolRisk.WRITE);
  }

  @Test
  void rejectsAMismatchedCapsuleBeforeReservationOrForget() {
    Scenario scenario = Scenario.approved();
    PendingOperationCapsule original = scenario.capsule;
    scenario.capsule =
        new PendingOperationCapsule(
            PendingOperationCapsule.SCHEMA_VERSION,
            "other-session",
            original.expectedNextSequence(),
            original.turnId(),
            original.callId(),
            original.toolName(),
            original.toolVersion(),
            original.risk(),
            original.canonicalArgumentsJson(),
            original.approvalId(),
            original.fingerprint(),
            original.idempotencyKey(),
            original.executionBoundaryVersion());

    assertThat(scenario.coordinator().resume(PendingOperationReference.of(OPERATION_REF)))
        .isEqualTo(MemoryForgetRecoveryCoordinator.Outcome.NOT_STARTED);

    assertThat(scenario.store().events).isEmpty();
    assertThat(scenario.port().commands).isEmpty();
    assertThat(scenario.store().operation.state())
        .isEqualTo(PendingOperationState.PENDING_APPROVAL);
  }

  @Test
  void recordsUnknownOnUncertainForgetAndNeverReplaysIt() {
    Scenario scenario = Scenario.approved();
    scenario.port().failure = new IllegalStateException("store response uncertain");

    assertThat(scenario.coordinator().resume(PendingOperationReference.of(OPERATION_REF)))
        .isEqualTo(MemoryForgetRecoveryCoordinator.Outcome.UNKNOWN);
    assertThat(scenario.coordinator().resume(PendingOperationReference.of(OPERATION_REF)))
        .isEqualTo(MemoryForgetRecoveryCoordinator.Outcome.NOT_STARTED);

    assertThat(scenario.port().commands).hasSize(1);
    assertThat(scenario.store().operation.state()).isEqualTo(PendingOperationState.UNKNOWN);
    assertThat(scenario.store().ledger)
        .hasValueSatisfying(
            ledger -> {
              assertThat(ledger.state()).isEqualTo(SideEffectExecutionState.UNKNOWN);
              assertThat(ledger.errorCode()).isEqualTo("MEMORY_FORGET_INVOKER_UNCERTAIN");
              assertThat(ledger.safeResult()).isEmpty();
            });
  }

  @Test
  void makesConversationCommitFailureTerminalWithoutReplayingForget() {
    Scenario scenario = Scenario.approved();
    scenario.sessions().commit = false;

    assertThat(scenario.coordinator().resume(PendingOperationReference.of(OPERATION_REF)))
        .isEqualTo(MemoryForgetRecoveryCoordinator.Outcome.COMMIT_UNREPORTED);
    assertThat(scenario.coordinator().resume(PendingOperationReference.of(OPERATION_REF)))
        .isEqualTo(MemoryForgetRecoveryCoordinator.Outcome.NOT_STARTED);

    assertThat(scenario.port().commands).hasSize(1);
    assertThat(scenario.store().operation.state())
        .isEqualTo(PendingOperationState.COMMIT_UNREPORTED);
    assertThat(scenario.store().ledger)
        .hasValueSatisfying(
            ledger -> {
              assertThat(ledger.state()).isEqualTo(SideEffectExecutionState.SUCCEEDED);
              assertThat(ledger.safeResult()).isPresent();
            });
  }

  private static final class Scenario {
    private final FakePendingOperationStore store;
    private final FakeSessions sessions;
    private final RecordingForgetPort port;
    private PendingOperationCapsule capsule;

    private Scenario(
        FakePendingOperationStore store,
        FakeSessions sessions,
        RecordingForgetPort port,
        PendingOperationCapsule capsule) {
      this.store = store;
      this.sessions = sessions;
      this.port = port;
      this.capsule = capsule;
      store.capsuleSource = () -> this.capsule;
    }

    static Scenario approved() {
      PendingOperation operation = operation();
      PendingOperationCapsule capsule =
          PendingOperationCapsule.forOperation(
              operation,
              "session-java-memory-001",
              "{\"ids\":[\" memory-a \",\"\",\"memory-b\",\"memory-a\"]}",
              MemoryForgetCapability.EXECUTION_BOUNDARY_VERSION);
      return new Scenario(
          new FakePendingOperationStore(operation),
          new FakeSessions(
              PendingTurnAnchor.pending(
                  OPERATION_REF,
                  "session-java-memory-001",
                  0,
                  MemoryForgetCapability.PROJECTION_VERSION)),
          new RecordingForgetPort(),
          capsule);
    }

    MemoryForgetRecoveryCoordinator coordinator() {
      return new MemoryForgetRecoveryCoordinator(
          store, sessions, new MemoryForgetCapability(port, CLOCK), CLOCK);
    }

    FakePendingOperationStore store() {
      return store;
    }

    FakeSessions sessions() {
      return sessions;
    }

    RecordingForgetPort port() {
      return port;
    }
  }

  private static PendingOperation operation() {
    String arguments = "{\"ids\":[\" memory-a \",\"\",\"memory-b\",\"memory-a\"]}";
    ApprovalRequest request =
        new ApprovalRequest(
            "approval-id",
            ApprovalFingerprint.sessionBinding("session-java-memory-001"),
            "turn-id",
            "call-id",
            "forget_memory",
            "java-memory-forget-v1",
            ToolRisk.WRITE,
            ApprovalFingerprint.argumentsHashJson(arguments),
            "idempotency-key",
            "受控记忆遗忘",
            NOW.minusSeconds(1),
            NOW.plusSeconds(300),
            ApprovalRequest.FINGERPRINT_VERSION,
            "a".repeat(64));
    return PendingOperation.pending(
        PendingOperationReference.of(OPERATION_REF), request, 2, NOW.minusSeconds(1));
  }

  private static final class RecordingForgetPort implements MemorySoftForgetPort {
    private final List<MemoryForgetCommand> commands = new ArrayList<>();
    private RuntimeException failure;

    @Override
    public MemoryForgetResult softForget(MemoryForgetCommand command) {
      commands.add(command);
      if (failure != null) {
        throw failure;
      }
      return new MemoryForgetResult(
          command.requestedIds(), List.of("memory-a"), List.of("memory-b"));
    }
  }

  private static final class FakePendingOperationStore implements PendingOperationStore {
    private PendingOperation operation;
    private Optional<PendingOperationLedgerEntry> ledger = Optional.empty();
    private final List<String> events = new ArrayList<>();
    private java.util.function.Supplier<PendingOperationCapsule> capsuleSource;

    private FakePendingOperationStore(PendingOperation operation) {
      this.operation = operation;
    }

    @Override
    public PendingOperation create(
        PendingOperation value, ApprovalInboxEntry approval, PendingOperationCapsule capsule) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<PendingOperation> find(PendingOperationReference reference) {
      return operation.reference().equals(reference) ? Optional.of(operation) : Optional.empty();
    }

    @Override
    public Optional<PendingOperationCapsule> loadVerifiedCapsule(
        PendingOperationReference reference) {
      return operation.reference().equals(reference)
          ? Optional.of(capsuleSource.get())
          : Optional.empty();
    }

    @Override
    public PendingOperationReservation reserveApproved(
        PendingOperationReference reference, Instant observedAt) {
      events.add("reserve");
      if (!operation.reference().equals(reference)
          || operation.state() != PendingOperationState.PENDING_APPROVAL) {
        return PendingOperationReservation.of(
            PendingOperationReservationStatus.NOT_RESERVABLE, operation);
      }
      operation =
          new PendingOperation(
              operation.reference(),
              operation.approval(),
              operation.expectedNextSequence(),
              PendingOperationState.CONSUMING,
              observedAt);
      return PendingOperationReservation.of(PendingOperationReservationStatus.RESERVED, operation);
    }

    @Override
    public PendingOperationLedgerEntry markRunning(
        PendingOperationReference reference, Instant observedAt) {
      events.add("running");
      return writeLedger(SideEffectExecutionState.RUNNING, Optional.empty(), "");
    }

    @Override
    public PendingOperationLedgerEntry markSucceeded(
        PendingOperationReference reference, ToolResult safeResult, Instant observedAt) {
      events.add("succeeded");
      operation = operation.transitionTo(PendingOperationState.SUCCEEDED, observedAt);
      return writeLedger(SideEffectExecutionState.SUCCEEDED, Optional.of(safeResult), "");
    }

    @Override
    public PendingOperationLedgerEntry markFailedBeforeStart(
        PendingOperationReference reference, ToolResult safeResult, Instant observedAt) {
      operation = operation.transitionTo(PendingOperationState.FAILED, observedAt);
      return writeLedger(SideEffectExecutionState.FAILED, Optional.of(safeResult), "");
    }

    @Override
    public PendingOperationLedgerEntry markUnknown(
        PendingOperationReference reference, String errorCode, Instant observedAt) {
      events.add("unknown");
      operation = operation.transitionTo(PendingOperationState.UNKNOWN, observedAt);
      return writeLedger(SideEffectExecutionState.UNKNOWN, Optional.empty(), errorCode);
    }

    @Override
    public PendingOperation markCommitUnreported(
        PendingOperationReference reference, Instant observedAt) {
      events.add("commit-unreported");
      operation = operation.transitionTo(PendingOperationState.COMMIT_UNREPORTED, observedAt);
      return operation;
    }

    @Override
    public Optional<PendingOperationLedgerEntry> findLedger(PendingOperationReference reference) {
      return ledger;
    }

    private PendingOperationLedgerEntry writeLedger(
        SideEffectExecutionState state, Optional<ToolResult> safeResult, String errorCode) {
      PendingOperationLedgerEntry value =
          new PendingOperationLedgerEntry(operation.reference(), state, safeResult, errorCode);
      ledger = Optional.of(value);
      return value;
    }
  }

  private static final class FakeSessions implements SessionRepository {
    private PendingTurnAnchor anchor;
    private boolean commit = true;
    private Optional<PendingTurnResolution> resolution = Optional.empty();

    private FakeSessions(PendingTurnAnchor anchor) {
      this.anchor = anchor;
    }

    @Override
    public SessionSnapshot load(String sessionId) {
      return new SessionSnapshot(sessionId, List.of(), anchor.resumeNextSequence());
    }

    @Override
    public void appendTurn(String sessionId, PersistedTurn turn) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<PendingTurnAnchor> findPendingTurnAnchor(String operationReference) {
      return anchor.operationReference().equals(operationReference)
          ? Optional.of(anchor)
          : Optional.empty();
    }

    @Override
    public boolean appendPendingResolutionIfAnchorMatches(
        PendingTurnAnchor expected, PendingTurnResolution value) {
      if (!commit || !anchor.equals(expected)) {
        return false;
      }
      resolution = Optional.of(value);
      anchor = anchor.transitionTo(io.namei.agent.kernel.model.PendingTurnAnchorState.COMMITTED);
      return true;
    }
  }
}
