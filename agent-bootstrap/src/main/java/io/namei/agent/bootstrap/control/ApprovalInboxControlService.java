package io.namei.agent.bootstrap.control;

import io.namei.agent.application.ApprovalInbox;
import io.namei.agent.application.ApprovalInboxDecision;
import io.namei.agent.application.ApprovalInboxReference;
import io.namei.agent.application.ApprovalInboxResolution;
import java.time.Clock;
import java.util.Objects;

/** Control-plane facade; it can decide an inbox entry but cannot resume a tool or a turn. */
public final class ApprovalInboxControlService {
  private static final int LIST_LIMIT = 64;

  private final Clock clock;
  private final ApprovalInbox inbox;

  public ApprovalInboxControlService(Clock clock, ApprovalInbox inbox) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.inbox = Objects.requireNonNull(inbox, "inbox");
  }

  public ApprovalInboxListResponse list() {
    return new ApprovalInboxListResponse(
        inbox.list(clock.instant(), LIST_LIMIT).stream()
            .map(ApprovalInboxItemResponse::from)
            .toList());
  }

  public ApprovalInboxResolution decide(
      String approvalRef, ApprovalInboxDecision decision, String actorReference) {
    return inbox.resolve(
        ApprovalInboxReference.of(approvalRef),
        Objects.requireNonNull(decision, "decision"),
        actorReference,
        clock.instant());
  }
}
