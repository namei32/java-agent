package io.namei.agent.application;

import java.time.Instant;
import java.util.List;

/**
 * Durable, local-only inbox boundary. It records a decision but intentionally never executes a tool
 * or resumes a turn.
 */
public interface ApprovalInbox {
  ApprovalInboxEntry create(ApprovalInboxEntry pending);

  List<ApprovalInboxEntry> list(Instant observedAt, int limit);

  ApprovalInboxResolution resolve(
      ApprovalInboxReference reference,
      ApprovalInboxDecision decision,
      String actorReference,
      Instant decidedAt);
}
