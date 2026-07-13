package io.namei.agent.kernel.model;

import java.util.Objects;

public record ChatMessage(MessageRole role, String content) {
  public ChatMessage {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(content, "content");
    content = content.trim();
    if (content.isEmpty()) {
      throw new IllegalArgumentException("消息内容不能为空");
    }
  }
}
