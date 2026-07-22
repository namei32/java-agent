package io.namei.agent.kernel.evidence;

import java.util.Objects;

/** 已校验的当前 Session 证据记录，不携带存储或 Route 元数据。 */
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
