package io.namei.agent.bootstrap.control;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.namei.agent.application.ApprovalFingerprint;
import io.namei.agent.application.ApprovalInboxEntry;
import io.namei.agent.application.MemoryForgetControlService;
import io.namei.agent.application.MemoryForgetRecovery;
import io.namei.agent.application.MemoryForgetRecoveryCoordinator;
import io.namei.agent.application.PendingOperation;
import io.namei.agent.application.PendingOperationCancelStatus;
import io.namei.agent.application.PendingOperationCapsule;
import io.namei.agent.application.PendingOperationLedgerEntry;
import io.namei.agent.application.PendingOperationReference;
import io.namei.agent.application.PendingOperationReservation;
import io.namei.agent.application.PendingOperationState;
import io.namei.agent.application.PendingOperationStore;
import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.model.PendingTurnAnchor;
import io.namei.agent.kernel.model.PendingTurnAnchorState;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import io.namei.agent.kernel.port.SessionRepository;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class PendingOperationControllerTest {
  private static final Instant NOW = Instant.parse("2026-07-19T10:00:00Z");
  private static final String REF = "AAAAAAAAAAAAAAAAAAAAAA";

  @Test
  void projectsOnlySafeStatusAndMapsResumeCancelAndErrorsToStableResponses() throws Exception {
    Store store = new Store(operation(PendingOperationState.PENDING_APPROVAL));
    Sessions sessions = new Sessions(anchor());
    MemoryForgetRecovery recovery =
        reference -> {
          store.operation = operation(PendingOperationState.SUCCEEDED);
          return MemoryForgetRecoveryCoordinator.Outcome.COMMITTED;
        };
    MvcFixture fixture = mvc(store, sessions, recovery);

    fixture
        .mvc()
        .perform(authenticated(get(path()), fixture.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schemaVersion").value(1))
        .andExpect(jsonPath("$.state").value("PENDING_APPROVAL"))
        .andExpect(jsonPath("$.updatedAt").value(NOW.toString()))
        .andExpect(jsonPath("$.approval").doesNotExist())
        .andExpect(jsonPath("$.reference").doesNotExist());

    fixture
        .mvc()
        .perform(authenticated(post(path("/resume")), fixture.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("SUCCEEDED"));

    store.operation = operation(PendingOperationState.PENDING_APPROVAL);
    fixture
        .mvc()
        .perform(authenticated(post(path("/cancel")), fixture.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("CANCELLED"));

    fixture
        .mvc()
        .perform(
            authenticated(
                get("/api/v1/control/pending-operations/BBBBBBBBBBBBBBBBBBBBBB"), fixture.token()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PENDING_RECOVERY_NOT_FOUND"))
        .andExpect(jsonPath("$.retryable").value(false));

    store.operation = operation(PendingOperationState.UNKNOWN);
    fixture
        .mvc()
        .perform(authenticated(post(path("/resume")), fixture.token()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PENDING_RECOVERY_UNKNOWN_REQUIRES_OPERATOR"));

    store.cancelStatus = PendingOperationCancelStatus.NOT_CANCELLABLE;
    fixture
        .mvc()
        .perform(authenticated(post(path("/cancel")), fixture.token()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PENDING_RECOVERY_NOT_CANCELLABLE"));
  }

  @Test
  void reportsRepositoryFailureAsRetryableWithoutLeakingOperationData() throws Exception {
    Store store = new Store(operation(PendingOperationState.PENDING_APPROVAL));
    store.failFind = true;
    MvcFixture fixture =
        mvc(
            store,
            new Sessions(anchor()),
            reference -> MemoryForgetRecoveryCoordinator.Outcome.NOT_STARTED);

    fixture
        .mvc()
        .perform(authenticated(get(path()), fixture.token()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("PENDING_RECOVERY_UNAVAILABLE"))
        .andExpect(jsonPath("$.retryable").value(true))
        .andExpect(jsonPath("$.reference").doesNotExist());
  }

  private static MvcFixture mvc(Store store, Sessions sessions, MemoryForgetRecovery recovery) {
    var service =
        new MemoryForgetControlService(store, sessions, recovery, Clock.fixed(NOW, ZoneOffset.UTC));
    var operatorSessions =
        new OperatorSessionStore(
            Clock.fixed(NOW, ZoneOffset.UTC),
            new OperatorSessionStoreTest.SequentialRandom(),
            Duration.ofMinutes(15),
            1,
            actor -> {});
    String token = operatorSessions.create().accessToken();
    MockMvc mvc =
        standaloneSetup(new PendingOperationController(service, ControlPlaneAudit.disabled()))
            .addFilters(
                new ControlPlaneSecurityFilter(
                    new LoopbackRequestGuard(),
                    operatorSessions,
                    ControlPlaneAudit.disabled(),
                    () -> "pending-operation-request",
                    new tools.jackson.databind.ObjectMapper()))
            .build();
    return new MvcFixture(mvc, token);
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      authenticated(
          org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
          String token) {
    return request
        .with(
            value -> {
              value.setRemoteAddr("127.0.0.1");
              value.setScheme("http");
              return value;
            })
        .header("Host", "127.0.0.1:8080")
        .header("Authorization", "Bearer " + token);
  }

  private static String path() {
    return path("");
  }

  private static String path(String suffix) {
    return "/api/v1/control/pending-operations/" + REF + suffix;
  }

  private static PendingTurnAnchor anchor() {
    return PendingTurnAnchor.pending(REF, "session-java-memory-001", 0, "pending-projection-v1");
  }

  private static PendingOperation operation(PendingOperationState state) {
    ApprovalRequest approval =
        new ApprovalRequest(
            "approval-id",
            ApprovalFingerprint.sessionBinding("session-java-memory-001"),
            "turn-id",
            "call-id",
            "forget_memory",
            "java-memory-forget-v1",
            ToolRisk.WRITE,
            ApprovalFingerprint.argumentsHashJson("{\"ids\":[\"memory-a\"]}"),
            "idempotency-key",
            "受控记忆遗忘",
            NOW,
            NOW.plusSeconds(300),
            ApprovalRequest.FINGERPRINT_VERSION,
            "a".repeat(64));
    return new PendingOperation(PendingOperationReference.of(REF), approval, 2, state, NOW);
  }

  private static final class Store implements PendingOperationStore {
    private PendingOperation operation;
    private PendingOperationCancelStatus cancelStatus = PendingOperationCancelStatus.CANCELLED;
    private boolean failFind;

    private Store(PendingOperation operation) {
      this.operation = operation;
    }

    @Override
    public PendingOperation create(
        PendingOperation value, ApprovalInboxEntry approval, PendingOperationCapsule capsule) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<PendingOperation> find(PendingOperationReference reference) {
      if (failFind) {
        throw new IllegalStateException("storage unavailable");
      }
      return operation.reference().equals(reference) ? Optional.of(operation) : Optional.empty();
    }

    @Override
    public PendingOperationCancelStatus cancelIfUnconsumed(
        PendingOperationReference reference, Instant observedAt) {
      if (cancelStatus == PendingOperationCancelStatus.CANCELLED) {
        operation = operation.transitionTo(PendingOperationState.CANCELLED, observedAt);
      }
      return cancelStatus;
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

  private static final class Sessions implements SessionRepository {
    private PendingTurnAnchor anchor;

    private Sessions(PendingTurnAnchor anchor) {
      this.anchor = anchor;
    }

    @Override
    public SessionSnapshot load(String sessionId) {
      return new SessionSnapshot(sessionId, List.of(), 2);
    }

    @Override
    public void appendTurn(String sessionId, PersistedTurn turn) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<PendingTurnAnchor> findPendingTurnAnchor(String operationReference) {
      return Optional.of(anchor);
    }

    @Override
    public boolean cancelPendingTurnAnchorIfMatches(PendingTurnAnchor value) {
      if (!anchor.equals(value)) {
        return false;
      }
      anchor = anchor.transitionTo(PendingTurnAnchorState.CANCELLED);
      return true;
    }
  }

  private record MvcFixture(MockMvc mvc, String token) {}
}
