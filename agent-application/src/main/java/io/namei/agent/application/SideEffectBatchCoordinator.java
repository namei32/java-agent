package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalDecision;
import io.namei.agent.kernel.approval.ApprovalDecisionStatus;
import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
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
  private final ToolApprovalGate gate = new ToolApprovalGate();

  SideEffectBatchCoordinator(
      ToolRegistry tools,
      ApprovalPort approvals,
      ToolExecutionPolicy policy,
      Clock clock,
      Duration approvalTimeout,
      IdGenerator ids) {
    this.tools = Objects.requireNonNull(tools, "tools");
    this.approvals = Objects.requireNonNull(approvals, "approvals");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.approvalTimeout = Objects.requireNonNull(approvalTimeout, "approvalTimeout");
    this.ids = Objects.requireNonNull(ids, "ids");
    if (approvalTimeout.isZero() || approvalTimeout.isNegative()) {
      throw new IllegalArgumentException("审批有效期必须大于零");
    }
  }

  List<ToolResult> execute(
      Context context, List<ToolCall> calls, TurnCancellation cancellation) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(calls, "calls");
    Objects.requireNonNull(cancellation, "cancellation");
    List<ToolRegistry.PreparedCall> prepared = tools.prepare(calls);
    List<ToolRisk> risks =
        prepared.stream()
            .map(
                item ->
                    item.definition() == null
                        ? ToolRisk.READ_ONLY
                        : ToolExecutionPolicy.effectiveRisk(
                            item.definition(), item.call(), policy))
            .toList();
    boolean hasSideEffect = risks.stream().anyMatch(risk -> risk != ToolRisk.READ_ONLY);
    if (!hasSideEffect) {
      return executeReadOnly(prepared, cancellation);
    }
    if (prepared.stream().anyMatch(item -> item.preflightFailure() != null)) {
      return preflightFailures(prepared);
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
      ApprovalDecision decision;
      try {
        decision = approvals.decide(request);
      } catch (RuntimeException exception) {
        throw new ApprovalUnavailableException();
      }
      decisions.add(gate.resolve(request, decision, clock.instant()));
    }

    if (decisions.stream()
        .filter(Objects::nonNull)
        .anyMatch(status -> status != ApprovalDecisionStatus.APPROVED)) {
      return deniedBatch(decisions);
    }

    var results = new ArrayList<ToolResult>(prepared.size());
    boolean stop = false;
    for (int index = 0; index < prepared.size(); index++) {
      if (stop || cancellation.isCancellationRequested()) {
        results.add(
            cancellation.isCancellationRequested() ? ToolResult.cancelled() : ToolResult.skipped());
        stop = true;
        continue;
      }
      var item = prepared.get(index);
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
      ToolResult result = tools.execute(item.call(), cancellation);
      results.add(result);
      if (currentRisk != ToolRisk.READ_ONLY && result.status() != ToolResultStatus.SUCCESS) {
        stop = true;
      }
    }
    return List.copyOf(results);
  }

  private List<ToolResult> executeReadOnly(
      List<ToolRegistry.PreparedCall> prepared, TurnCancellation cancellation) {
    return prepared.stream()
        .map(
            item ->
                item.preflightFailure() == null
                    ? tools.execute(item.call(), cancellation)
                    : item.preflightFailure())
        .toList();
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
