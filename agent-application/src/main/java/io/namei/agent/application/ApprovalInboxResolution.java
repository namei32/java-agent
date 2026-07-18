package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalState;
import java.util.Objects;
import java.util.Optional;

/** A repository-authoritative result; callers must not infer a decision from an update count. */
public record ApprovalInboxResolution(
    ApprovalInboxResolutionStatus status, Optional<ApprovalInboxEntry> entry) {
  public ApprovalInboxResolution {
    status = Objects.requireNonNull(status, "status");
    entry = Objects.requireNonNull(entry, "entry");
    switch (status) {
      case NOT_FOUND -> {
        if (entry.isPresent()) {
          throw new IllegalArgumentException("未找到审批不能携带记录");
        }
      }
      case RESOLVED -> requireState(entry, ApprovalState.APPROVED, ApprovalState.DENIED);
      case ALREADY_RESOLVED ->
          requireState(
              entry, ApprovalState.APPROVED, ApprovalState.DENIED, ApprovalState.CANCELLED);
      case EXPIRED -> requireState(entry, ApprovalState.EXPIRED);
    }
  }

  public static ApprovalInboxResolution resolved(ApprovalInboxEntry entry) {
    return new ApprovalInboxResolution(ApprovalInboxResolutionStatus.RESOLVED, Optional.of(entry));
  }

  public static ApprovalInboxResolution alreadyResolved(ApprovalInboxEntry entry) {
    return new ApprovalInboxResolution(
        ApprovalInboxResolutionStatus.ALREADY_RESOLVED, Optional.of(entry));
  }

  public static ApprovalInboxResolution expired(ApprovalInboxEntry entry) {
    return new ApprovalInboxResolution(ApprovalInboxResolutionStatus.EXPIRED, Optional.of(entry));
  }

  public static ApprovalInboxResolution notFound() {
    return new ApprovalInboxResolution(ApprovalInboxResolutionStatus.NOT_FOUND, Optional.empty());
  }

  private static void requireState(Optional<ApprovalInboxEntry> entry, ApprovalState... allowed) {
    ApprovalInboxEntry value = entry.orElseThrow(() -> new IllegalArgumentException("审批记录缺失"));
    for (ApprovalState state : allowed) {
      if (value.state() == state) {
        return;
      }
    }
    throw new IllegalArgumentException("审批记录状态不匹配");
  }

  @Override
  public String toString() {
    return "ApprovalInboxResolution[status=" + status + ", entry=<redacted>]";
  }
}
