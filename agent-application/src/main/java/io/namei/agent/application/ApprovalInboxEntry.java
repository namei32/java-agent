package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.approval.ApprovalState;
import java.time.Instant;
import java.util.Objects;

/** Durable approval record. Its request binding must never be rendered by public adapters. */
public record ApprovalInboxEntry(
    ApprovalInboxReference reference,
    ApprovalRequest request,
    ApprovalState state,
    Instant decidedAt,
    String actorReference) {
  public ApprovalInboxEntry {
    reference = Objects.requireNonNull(reference, "reference");
    request = Objects.requireNonNull(request, "request");
    state = Objects.requireNonNull(state, "state");
    actorReference = Objects.requireNonNullElse(actorReference, "").strip();
    if (state == ApprovalState.CONSUMED) {
      throw new IllegalArgumentException("审批收件箱不接受已消费状态");
    }
    if (state == ApprovalState.PENDING) {
      if (decidedAt != null || !actorReference.isEmpty()) {
        throw new IllegalArgumentException("待审批记录不能包含决定信息");
      }
    } else {
      if (decidedAt == null || decidedAt.isBefore(request.issuedAt())) {
        throw new IllegalArgumentException("终态审批记录的决定时间无效");
      }
      boolean requiresActor = state == ApprovalState.APPROVED || state == ApprovalState.DENIED;
      if (requiresActor != !actorReference.isEmpty()) {
        throw new IllegalArgumentException("审批记录的决定主体无效");
      }
    }
  }

  public static ApprovalInboxEntry pending(
      ApprovalInboxReference reference, ApprovalRequest request) {
    return new ApprovalInboxEntry(reference, request, ApprovalState.PENDING, null, "");
  }

  @Override
  public String toString() {
    return "ApprovalInboxEntry[reference=<redacted>, request=<redacted>, state="
        + state
        + ", decidedAt="
        + decidedAt
        + ", actorReference=<redacted>]";
  }
}
