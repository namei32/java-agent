package io.namei.agent.kernel.model;

import java.util.List;
import java.util.Objects;

public record SessionSnapshot(String sessionId, List<ChatMessage> messages, long nextSequence) {
  public SessionSnapshot {
    Objects.requireNonNull(sessionId, "sessionId");
    messages = List.copyOf(messages);
    if (nextSequence < 0) {
      throw new IllegalArgumentException("nextSequence 不能为负数");
    }
  }
}
