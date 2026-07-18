package io.namei.agent.kernel.evidence;

import java.util.List;
import java.util.Objects;

/** A bounded, ordered current-session search page plus its non-sensitive page metadata. */
public record ConversationEvidencePage(
    List<ConversationEvidenceMessage> messages, int matchedCount, boolean hasMore) {
  public ConversationEvidencePage {
    messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    if (matchedCount < messages.size()) {
      throw new IllegalArgumentException("会话证据命中总数不能小于当前页");
    }
  }
}
