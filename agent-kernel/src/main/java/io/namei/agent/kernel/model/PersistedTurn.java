package io.namei.agent.kernel.model;

import java.time.OffsetDateTime;
import java.util.Objects;

public record PersistedTurn(
    ChatMessage user, OffsetDateTime userAt, ChatMessage assistant, OffsetDateTime assistantAt) {
  public PersistedTurn {
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(userAt, "userAt");
    Objects.requireNonNull(assistant, "assistant");
    Objects.requireNonNull(assistantAt, "assistantAt");
    if (user.role() != MessageRole.USER || assistant.role() != MessageRole.ASSISTANT) {
      throw new IllegalArgumentException("持久化轮次必须由 user 和 assistant 组成");
    }
  }
}
