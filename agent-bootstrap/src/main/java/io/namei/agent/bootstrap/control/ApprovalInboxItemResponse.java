package io.namei.agent.bootstrap.control;

import io.namei.agent.application.ApprovalInboxEntry;
import java.time.Instant;
import java.util.Objects;

/** 公开投影有意排除所有执行绑定字段和操作员身份。 */
public record ApprovalInboxItemResponse(
    String approvalRef,
    String toolName,
    String toolVersion,
    String risk,
    String summary,
    Instant issuedAt,
    Instant expiresAt,
    String state,
    Instant decidedAt) {
  static ApprovalInboxItemResponse from(ApprovalInboxEntry entry) {
    Objects.requireNonNull(entry, "entry");
    return new ApprovalInboxItemResponse(
        entry.reference().value(),
        entry.request().toolName(),
        entry.request().toolVersion(),
        entry.request().risk().name(),
        entry.request().summary(),
        entry.request().issuedAt(),
        entry.request().expiresAt(),
        entry.state().name(),
        entry.decidedAt());
  }
}
