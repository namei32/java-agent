package io.namei.agent.application;

import java.time.Instant;
import java.util.List;

/** 仅限本地的持久 Inbox 边界。它记录决议，但有意永不执行 Tool 或恢复 Turn。 */
public interface ApprovalInbox {
  ApprovalInboxEntry create(ApprovalInboxEntry pending);

  List<ApprovalInboxEntry> list(Instant observedAt, int limit);

  ApprovalInboxResolution resolve(
      ApprovalInboxReference reference,
      ApprovalInboxDecision decision,
      String actorReference,
      Instant decidedAt);
}
