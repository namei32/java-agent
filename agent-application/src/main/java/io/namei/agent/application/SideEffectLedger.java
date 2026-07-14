package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.tool.SideEffectExecutionState;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolResultStatus;
import java.util.Objects;
import java.util.Optional;

public interface SideEffectLedger {
  Reservation reserve(SideEffectIdentity identity, ApprovalRequest approval);

  void markRunning(SideEffectIdentity identity);

  void markSucceeded(SideEffectIdentity identity, ToolResult safeResult);

  void markFailedBeforeStart(SideEffectIdentity identity, ToolResult safeResult);

  void markUnknown(SideEffectIdentity identity, String errorCode);

  Optional<Entry> find(SideEffectIdentity identity);

  static SideEffectLedger unavailable() {
    return UnavailableHolder.INSTANCE;
  }

  record Reservation(boolean acquired, Entry entry) {
    public Reservation {
      Objects.requireNonNull(entry, "entry");
      if (acquired && entry.state() != SideEffectExecutionState.RESERVED) {
        throw new IllegalArgumentException("新 Ledger Reservation 必须处于 RESERVED");
      }
    }
  }

  record Entry(
      SideEffectIdentity identity,
      SideEffectExecutionState state,
      ToolResult safeResult,
      String errorCode) {
    public Entry {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(state, "state");
      errorCode = Objects.requireNonNullElse(errorCode, "").strip();
      boolean terminalResult =
          state == SideEffectExecutionState.SUCCEEDED
              || state == SideEffectExecutionState.FAILED;
      if (terminalResult != (safeResult != null)) {
        throw new IllegalArgumentException("Ledger 终态安全结果不完整");
      }
      if (state == SideEffectExecutionState.SUCCEEDED
          && safeResult.status() != ToolResultStatus.SUCCESS) {
        throw new IllegalArgumentException("SUCCEEDED 必须保存成功结果");
      }
      if (state == SideEffectExecutionState.FAILED
          && safeResult.status() == ToolResultStatus.SUCCESS) {
        throw new IllegalArgumentException("FAILED 不能保存成功结果");
      }
      if (state == SideEffectExecutionState.UNKNOWN && errorCode.isBlank()) {
        throw new IllegalArgumentException("UNKNOWN 必须包含稳定错误码");
      }
      if (state != SideEffectExecutionState.UNKNOWN && !errorCode.isEmpty()) {
        throw new IllegalArgumentException("只有 UNKNOWN 可以包含错误码");
      }
    }
  }

  final class UnavailableHolder {
    private static final SideEffectLedger INSTANCE =
        new SideEffectLedger() {
          @Override
          public Reservation reserve(SideEffectIdentity identity, ApprovalRequest approval) {
            throw new ApprovalUnavailableException();
          }

          @Override
          public void markRunning(SideEffectIdentity identity) {
            throw new ApprovalUnavailableException();
          }

          @Override
          public void markSucceeded(SideEffectIdentity identity, ToolResult safeResult) {
            throw new ApprovalUnavailableException();
          }

          @Override
          public void markFailedBeforeStart(
              SideEffectIdentity identity, ToolResult safeResult) {
            throw new ApprovalUnavailableException();
          }

          @Override
          public void markUnknown(SideEffectIdentity identity, String errorCode) {
            throw new ApprovalUnavailableException();
          }

          @Override
          public Optional<Entry> find(SideEffectIdentity identity) {
            throw new ApprovalUnavailableException();
          }
        };

    private UnavailableHolder() {}
  }
}
