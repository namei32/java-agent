package io.namei.agent.kernel.evidence;

import java.util.Objects;

/** A validated, current-session evidence record. No storage or route metadata is carried. */
public record ConversationEvidenceMessage(
    ConversationEvidenceReference reference, ConversationEvidenceRole role, String content) {
  public ConversationEvidenceMessage {
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(content, "content");
    if (content.isBlank()) {
      throw new IllegalArgumentException("会话证据内容不能为空");
    }
  }
}
