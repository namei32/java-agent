package io.namei.agent.application;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import java.util.Objects;

public record ChatResult(String sessionId, ChatMessage assistant) {
  public ChatResult {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(assistant, "assistant");
    if (assistant.role() != MessageRole.ASSISTANT) {
      throw new IllegalArgumentException("assistant 必须使用 ASSISTANT 角色");
    }
  }
}
