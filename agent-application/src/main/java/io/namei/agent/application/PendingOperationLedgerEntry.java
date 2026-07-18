package io.namei.agent.application;

import io.namei.agent.kernel.tool.SideEffectExecutionState;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolResultStatus;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Durable, non-executing projection of one pending operation's side-effect boundary.
 *
 * <p>Its result is already a capability-supplied safe projection. This type deliberately omits the
 * encrypted capsule, approval binding and any raw Tool arguments.
 */
public record PendingOperationLedgerEntry(
    PendingOperationReference reference,
    SideEffectExecutionState state,
    Optional<ToolResult> safeResult,
    String errorCode) {
  private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

  public PendingOperationLedgerEntry {
    reference = Objects.requireNonNull(reference, "reference");
    state = Objects.requireNonNull(state, "state");
    safeResult = Objects.requireNonNull(safeResult, "safeResult");
    errorCode = Objects.requireNonNullElse(errorCode, "").strip();
    if (safeResult.map(result -> result.content().length() > 4_096).orElse(false)) {
      throw new IllegalArgumentException("安全 Tool 结果超出 Ledger 上限");
    }
    boolean terminalResult =
        state == SideEffectExecutionState.SUCCEEDED || state == SideEffectExecutionState.FAILED;
    if (terminalResult != safeResult.isPresent()) {
      throw new IllegalArgumentException("Ledger 终态安全结果不完整");
    }
    if (state == SideEffectExecutionState.SUCCEEDED
        && safeResult.orElseThrow().status() != ToolResultStatus.SUCCESS) {
      throw new IllegalArgumentException("SUCCEEDED 必须保存成功结果");
    }
    if (state == SideEffectExecutionState.FAILED
        && safeResult.orElseThrow().status() == ToolResultStatus.SUCCESS) {
      throw new IllegalArgumentException("FAILED 不能保存成功结果");
    }
    if (state == SideEffectExecutionState.UNKNOWN) {
      if (!ERROR_CODE.matcher(errorCode).matches()) {
        throw new IllegalArgumentException("UNKNOWN 必须包含稳定错误码");
      }
    } else if (!errorCode.isEmpty()) {
      throw new IllegalArgumentException("只有 UNKNOWN 可以包含错误码");
    }
  }

  @Override
  public String toString() {
    return "PendingOperationLedgerEntry[reference=<redacted>, state="
        + state
        + ", safeResult=<redacted>, errorCode="
        + errorCode
        + "]";
  }
}
