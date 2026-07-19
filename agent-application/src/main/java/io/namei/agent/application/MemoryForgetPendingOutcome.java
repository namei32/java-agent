package io.namei.agent.application;

import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolResultStatus;
import java.util.Objects;

/** Safe internal result of requesting the one approved memory forget operation. */
public sealed interface MemoryForgetPendingOutcome
    permits MemoryForgetPendingOutcome.ImmediateSuccess,
        MemoryForgetPendingOutcome.Pending,
        MemoryForgetPendingOutcome.StaleSession {
  record ImmediateSuccess(ToolResult safeResult) implements MemoryForgetPendingOutcome {
    public ImmediateSuccess {
      safeResult = Objects.requireNonNull(safeResult, "safeResult");
      if (safeResult.status() != ToolResultStatus.SUCCESS) {
        throw new IllegalArgumentException("空 Forget 只能产生安全成功结果");
      }
    }

    @Override
    public String toString() {
      return "MemoryForgetPendingOutcome.ImmediateSuccess[safeResult=<redacted>]";
    }
  }

  record Pending(PendingOperationReference reference, ApprovalInboxReference approvalReference)
      implements MemoryForgetPendingOutcome {
    public Pending {
      reference = Objects.requireNonNull(reference, "reference");
      approvalReference = Objects.requireNonNull(approvalReference, "approvalReference");
    }

    @Override
    public String toString() {
      return "MemoryForgetPendingOutcome.Pending[reference=<redacted>, approvalReference=<redacted>]";
    }
  }

  record StaleSession(PendingOperationReference reference) implements MemoryForgetPendingOutcome {
    public StaleSession {
      reference = Objects.requireNonNull(reference, "reference");
    }

    @Override
    public String toString() {
      return "MemoryForgetPendingOutcome.StaleSession[reference=<redacted>]";
    }
  }
}
