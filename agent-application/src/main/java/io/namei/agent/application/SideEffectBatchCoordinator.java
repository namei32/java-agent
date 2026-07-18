package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalDecision;
import io.namei.agent.kernel.approval.ApprovalDecisionStatus;
import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.lifecycle.TurnLifecycleEvent;
import io.namei.agent.kernel.port.TurnLifecycleObserver;
import io.namei.agent.kernel.tool.SideEffectExecutionState;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolResultStatus;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class SideEffectBatchCoordinator {
  private final ToolRegistry tools;
  private final ApprovalPort approvals;
  private final ToolExecutionPolicy policy;
  private final Clock clock;
  private final Duration approvalTimeout;
  private final IdGenerator ids;
  private final SideEffectLedger ledger;
  private final LifecyclePublisher lifecycle;
  private final ToolApprovalGate gate = new ToolApprovalGate();

  SideEffectBatchCoordinator(
      ToolRegistry tools,
      ApprovalPort approvals,
      ToolExecutionPolicy policy,
      Clock clock,
      Duration approvalTimeout,
      IdGenerator ids) {
    this(
        tools,
        approvals,
        policy,
        clock,
        approvalTimeout,
        ids,
        SideEffectLedger.unavailable(),
        new LifecyclePublisher(TurnLifecycleObserver.noop()));
  }

  SideEffectBatchCoordinator(
      ToolRegistry tools,
      ApprovalPort approvals,
      ToolExecutionPolicy policy,
      Clock clock,
      Duration approvalTimeout,
      IdGenerator ids,
      SideEffectLedger ledger) {
    this(
        tools,
        approvals,
        policy,
        clock,
        approvalTimeout,
        ids,
        ledger,
        new LifecyclePublisher(TurnLifecycleObserver.noop()));
  }

  SideEffectBatchCoordinator(
      ToolRegistry tools,
      ApprovalPort approvals,
      ToolExecutionPolicy policy,
      Clock clock,
      Duration approvalTimeout,
      IdGenerator ids,
      SideEffectLedger ledger,
      LifecyclePublisher lifecycle) {
    this.tools = Objects.requireNonNull(tools, "tools");
    this.approvals = Objects.requireNonNull(approvals, "approvals");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.approvalTimeout = Objects.requireNonNull(approvalTimeout, "approvalTimeout");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    if (approvalTimeout.isZero() || approvalTimeout.isNegative()) {
      throw new IllegalArgumentException("审批有效期必须大于零");
    }
  }

  List<ToolResult> execute(Context context, List<ToolCall> calls, TurnCancellation cancellation) {
    return execute(context, 1, calls, cancellation, tools.newCatalogSession());
  }

  List<ToolResult> execute(
      Context context, int iteration, List<ToolCall> calls, TurnCancellation cancellation) {
    return execute(context, iteration, calls, cancellation, tools.newCatalogSession());
  }

  List<ToolResult> execute(
      Context context,
      int iteration,
      List<ToolCall> calls,
      TurnCancellation cancellation,
      ToolCatalogSession catalogSession) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(calls, "calls");
    Objects.requireNonNull(cancellation, "cancellation");
    Objects.requireNonNull(catalogSession, "catalogSession");
    List<ToolRegistry.PreparedCall> prepared = tools.prepare(calls, catalogSession);
    List<ToolRisk> risks =
        prepared.stream()
            .map(
                item ->
                    item.definition() == null
                        ? ToolRisk.READ_ONLY
                        : ToolExecutionPolicy.effectiveRisk(item.definition(), item.call(), policy))
            .toList();
    boolean hasSideEffect = risks.stream().anyMatch(risk -> risk != ToolRisk.READ_ONLY);
    if (!hasSideEffect) {
      return executeReadOnly(prepared, iteration, cancellation, catalogSession);
    }
    prepared.forEach(
        item ->
            lifecycle.emit(
                TurnLifecycleEvent.toolStarted(iteration, item.call().id(), item.call().name())));
    if (prepared.stream().anyMatch(item -> item.preflightFailure() != null)) {
      var results = preflightFailures(prepared);
      completeAll(iteration, prepared, results);
      return results;
    }

    Instant issuedAt = clock.instant();
    Instant expiresAt;
    try {
      expiresAt = issuedAt.plus(approvalTimeout);
    } catch (RuntimeException exception) {
      throw new ApprovalUnavailableException();
    }
    var requests = new ArrayList<ApprovalRequest>(calls.size());
    var decisions = new ArrayList<ApprovalDecisionStatus>(calls.size());
    for (int index = 0; index < prepared.size(); index++) {
      if (risks.get(index) == ToolRisk.READ_ONLY) {
        requests.add(null);
        decisions.add(null);
        continue;
      }
      var item = prepared.get(index);
      var request =
          gate.request(
              context,
              item.call(),
              item.definition(),
              risks.get(index),
              ids.newApprovalId(),
              ids.newIdempotencyKey(),
              issuedAt,
              expiresAt);
      requests.add(request);
      lifecycle.emit(
          TurnLifecycleEvent.approvalRequested(iteration, item.call().id(), item.call().name()));
      ApprovalDecision decision;
      try {
        decision = approvals.decide(request);
      } catch (RuntimeException exception) {
        throw new ApprovalUnavailableException();
      }
      ApprovalDecisionStatus status = gate.resolve(request, decision, clock.instant());
      decisions.add(status);
      lifecycle.emit(
          TurnLifecycleEvent.approvalResolved(
              iteration, item.call().id(), item.call().name(), status));
    }

    if (decisions.stream()
        .filter(Objects::nonNull)
        .anyMatch(status -> status != ApprovalDecisionStatus.APPROVED)) {
      var results = deniedBatch(decisions);
      completeAll(iteration, prepared, results);
      return results;
    }

    var results = new ArrayList<ToolResult>(prepared.size());
    boolean stop = false;
    for (int index = 0; index < prepared.size(); index++) {
      var item = prepared.get(index);
      if (stop || cancellation.isCancellationRequested()) {
        ToolResult result =
            cancellation.isCancellationRequested() ? ToolResult.cancelled() : ToolResult.skipped();
        results.add(result);
        lifecycle.emit(
            TurnLifecycleEvent.toolCompleted(
                iteration, item.call().id(), item.call().name(), result.status()));
        stop = true;
        continue;
      }
      ToolRisk currentRisk =
          item.definition() == null
              ? ToolRisk.READ_ONLY
              : ToolExecutionPolicy.effectiveRisk(item.definition(), item.call(), policy);
      if (risks.get(index) != currentRisk) {
        throw new ApprovalUnavailableException();
      }
      if (currentRisk != ToolRisk.READ_ONLY) {
        gate.revalidate(
            requests.get(index),
            context,
            item.call(),
            item.definition(),
            currentRisk,
            clock.instant());
      }
      ToolResult result =
          currentRisk == ToolRisk.READ_ONLY
              ? tools.execute(item.call(), cancellation, catalogSession)
              : executeSideEffect(
                  requests.get(index), item.call(), iteration, cancellation, catalogSession);
      results.add(result);
      lifecycle.emit(
          TurnLifecycleEvent.toolCompleted(
              iteration, item.call().id(), item.call().name(), result.status()));
      if (currentRisk != ToolRisk.READ_ONLY && result.status() != ToolResultStatus.SUCCESS) {
        stop = true;
      }
    }
    return List.copyOf(results);
  }

  private ToolResult executeSideEffect(
      ApprovalRequest request,
      ToolCall call,
      int iteration,
      TurnCancellation cancellation,
      ToolCatalogSession catalogSession) {
    SideEffectIdentity identity = SideEffectIdentity.from(request);
    SideEffectLedger.Reservation reservation;
    try {
      reservation = ledger.reserve(identity, request);
    } catch (SideEffectStateUnknownException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new ApprovalUnavailableException();
    }
    if (!reservation.entry().identity().equals(identity)) {
      throw new ApprovalUnavailableException();
    }
    if (!reservation.acquired()) {
      return replay(reservation.entry());
    }
    if (cancellation.isCancellationRequested()) {
      ToolResult cancelled = ToolResult.cancelled();
      try {
        ledger.markFailedBeforeStart(identity, cancelled);
        return cancelled;
      } catch (RuntimeException exception) {
        throw new ApprovalUnavailableException();
      }
    }
    try {
      ledger.markRunning(identity);
    } catch (RuntimeException exception) {
      throw new ApprovalUnavailableException();
    }
    lifecycle.emit(TurnLifecycleEvent.sideEffectStarted(iteration, call.id(), call.name()));

    ToolResult result;
    try {
      result = tools.execute(call, cancellation, catalogSession);
    } catch (RuntimeException exception) {
      recordUnknown(identity, "SIDE_EFFECT_INVOCATION_UNCERTAIN");
      lifecycle.emit(
          TurnLifecycleEvent.sideEffectCompleted(
              iteration, call.id(), call.name(), SideEffectExecutionState.UNKNOWN));
      throw new SideEffectStateUnknownException();
    }
    if (result.status() != ToolResultStatus.SUCCESS) {
      recordUnknown(identity, "SIDE_EFFECT_RESULT_UNCERTAIN");
      lifecycle.emit(
          TurnLifecycleEvent.sideEffectCompleted(
              iteration, call.id(), call.name(), SideEffectExecutionState.UNKNOWN));
      throw new SideEffectStateUnknownException();
    }
    try {
      ledger.markSucceeded(identity, result);
      lifecycle.emit(
          TurnLifecycleEvent.sideEffectCompleted(
              iteration, call.id(), call.name(), ToolResultStatus.SUCCESS));
      return result;
    } catch (RuntimeException exception) {
      recordUnknown(identity, "SIDE_EFFECT_SUCCESS_PERSISTENCE_FAILED");
      lifecycle.emit(
          TurnLifecycleEvent.sideEffectCompleted(
              iteration, call.id(), call.name(), SideEffectExecutionState.UNKNOWN));
      throw new SideEffectStateUnknownException();
    }
  }

  private static ToolResult replay(SideEffectLedger.Entry entry) {
    return switch (entry.state()) {
      case SUCCEEDED, FAILED -> entry.safeResult();
      case RESERVED, RUNNING, UNKNOWN -> throw new SideEffectStateUnknownException();
    };
  }

  private void recordUnknown(SideEffectIdentity identity, String errorCode) {
    try {
      ledger.markUnknown(identity, errorCode);
    } catch (RuntimeException ignored) {
      // The public outcome remains UNKNOWN even when its durable update also fails.
    }
  }

  private List<ToolResult> executeReadOnly(
      List<ToolRegistry.PreparedCall> prepared,
      int iteration,
      TurnCancellation cancellation,
      ToolCatalogSession catalogSession) {
    var results = new ArrayList<ToolResult>(prepared.size());
    for (var item : prepared) {
      lifecycle.emit(
          TurnLifecycleEvent.toolStarted(iteration, item.call().id(), item.call().name()));
      ToolResult result =
          item.preflightFailure() == null
              ? tools.execute(item.call(), cancellation, catalogSession)
              : item.preflightFailure();
      results.add(result);
      lifecycle.emit(
          TurnLifecycleEvent.toolCompleted(
              iteration, item.call().id(), item.call().name(), result.status()));
    }
    return List.copyOf(results);
  }

  private static List<ToolResult> preflightFailures(List<ToolRegistry.PreparedCall> prepared) {
    return prepared.stream()
        .map(
            item ->
                item.preflightFailure() == null ? ToolResult.skipped() : item.preflightFailure())
        .toList();
  }

  private static List<ToolResult> deniedBatch(List<ApprovalDecisionStatus> decisions) {
    return decisions.stream()
        .map(
            status -> {
              if (status == null || status == ApprovalDecisionStatus.APPROVED) {
                return ToolResult.skipped();
              }
              return switch (status) {
                case DENIED -> ToolResult.denied();
                case EXPIRED -> ToolResult.approvalExpired();
                case CANCELLED -> ToolResult.cancelled();
                case APPROVED -> ToolResult.skipped();
              };
            })
        .toList();
  }

  private void completeAll(
      int iteration, List<ToolRegistry.PreparedCall> prepared, List<ToolResult> results) {
    for (int index = 0; index < prepared.size(); index++) {
      var item = prepared.get(index);
      lifecycle.emit(
          TurnLifecycleEvent.toolCompleted(
              iteration, item.call().id(), item.call().name(), results.get(index).status()));
    }
  }

  record Context(String sessionBinding, String turnId) {
    Context {
      sessionBinding = required(sessionBinding, "Session Binding");
      turnId = required(turnId, "Turn ID");
    }

    private static String required(String value, String field) {
      Objects.requireNonNull(value, field);
      var normalized = value.strip();
      if (normalized.isBlank()) {
        throw new IllegalArgumentException(field + " 不能为空");
      }
      return normalized;
    }
  }
}
