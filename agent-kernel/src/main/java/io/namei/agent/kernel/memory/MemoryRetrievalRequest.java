package io.namei.agent.kernel.memory;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record MemoryRetrievalRequest(
    String sessionBinding, String currentMessage, List<ChatMessage> history, Instant requestedAt) {
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

  public MemoryRetrievalRequest {
    sessionBinding = required(sessionBinding, "Session Binding");
    if (!SHA_256.matcher(sessionBinding).matches()) {
      throw new IllegalArgumentException("Session Binding 必须为 SHA-256");
    }
    currentMessage = required(currentMessage, "当前消息");
    history = List.copyOf(Objects.requireNonNull(history, "history"));
    for (var message : history) {
      Objects.requireNonNull(message, "history message");
      if (message.role() != MessageRole.USER && message.role() != MessageRole.ASSISTANT) {
        throw new IllegalArgumentException("检索历史只能包含已持久化的 User/Assistant 消息");
      }
    }
    Objects.requireNonNull(requestedAt, "requestedAt");
  }

  private static String required(String value, String field) {
    Objects.requireNonNull(value, field);
    var normalized = value.strip();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return normalized;
  }
}
