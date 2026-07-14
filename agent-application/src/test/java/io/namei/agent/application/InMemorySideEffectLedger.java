package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.tool.SideEffectExecutionState;
import io.namei.agent.kernel.tool.ToolResult;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class InMemorySideEffectLedger implements SideEffectLedger {
  private final Map<String, Entry> entries = new HashMap<>();
  private final Map<String, String> approvalOperations = new HashMap<>();
  private boolean failMarkRunning;
  private boolean failMarkSucceeded;

  @Override
  public synchronized Reservation reserve(
      SideEffectIdentity identity, ApprovalRequest approval) {
    Entry existing = entries.get(identity.idempotencyKey());
    if (existing != null) {
      if (!existing.identity().equals(identity)) {
        throw new IllegalStateException("幂等键已绑定其他操作");
      }
      return new Reservation(false, existing);
    }
    String operation = approvalOperations.get(approval.approvalId());
    if (operation != null && !operation.equals(identity.idempotencyKey())) {
      throw new IllegalStateException("审批已经消费");
    }
    var entry = new Entry(identity, SideEffectExecutionState.RESERVED, null, "");
    entries.put(identity.idempotencyKey(), entry);
    approvalOperations.put(approval.approvalId(), identity.idempotencyKey());
    return new Reservation(true, entry);
  }

  @Override
  public synchronized void markRunning(SideEffectIdentity identity) {
    if (failMarkRunning) {
      failMarkRunning = false;
      throw new IllegalStateException("测试 Ledger RUNNING 故障");
    }
    transition(identity, SideEffectExecutionState.RESERVED, SideEffectExecutionState.RUNNING, null, "");
  }

  @Override
  public synchronized void markSucceeded(SideEffectIdentity identity, ToolResult safeResult) {
    if (failMarkSucceeded) {
      failMarkSucceeded = false;
      throw new IllegalStateException("测试 Ledger SUCCEEDED 故障");
    }
    transition(
        identity,
        SideEffectExecutionState.RUNNING,
        SideEffectExecutionState.SUCCEEDED,
        safeResult,
        "");
  }

  @Override
  public synchronized void markFailedBeforeStart(
      SideEffectIdentity identity, ToolResult safeResult) {
    transition(
        identity,
        SideEffectExecutionState.RESERVED,
        SideEffectExecutionState.FAILED,
        safeResult,
        "");
  }

  @Override
  public synchronized void markUnknown(SideEffectIdentity identity, String errorCode) {
    transition(
        identity,
        SideEffectExecutionState.RUNNING,
        SideEffectExecutionState.UNKNOWN,
        null,
        errorCode);
  }

  @Override
  public synchronized Optional<Entry> find(SideEffectIdentity identity) {
    Entry entry = entries.get(identity.idempotencyKey());
    if (entry != null && !entry.identity().equals(identity)) {
      throw new IllegalStateException("幂等键已绑定其他操作");
    }
    return Optional.ofNullable(entry);
  }

  synchronized void seed(
      SideEffectIdentity identity, SideEffectExecutionState state, ToolResult safeResult) {
    String errorCode =
        state == SideEffectExecutionState.UNKNOWN ? "SIDE_EFFECT_STATE_UNKNOWN" : "";
    entries.put(identity.idempotencyKey(), new Entry(identity, state, safeResult, errorCode));
    approvalOperations.put(identity.approvalId(), identity.idempotencyKey());
  }

  synchronized void failNextMarkRunning() {
    failMarkRunning = true;
  }

  synchronized void failNextMarkSucceeded() {
    failMarkSucceeded = true;
  }

  private void transition(
      SideEffectIdentity identity,
      SideEffectExecutionState expected,
      SideEffectExecutionState next,
      ToolResult safeResult,
      String errorCode) {
    Entry current = entries.get(identity.idempotencyKey());
    if (current == null || !current.identity().equals(identity) || current.state() != expected) {
      throw new IllegalStateException("Ledger 状态转换无效");
    }
    entries.put(identity.idempotencyKey(), new Entry(identity, next, safeResult, errorCode));
  }
}
