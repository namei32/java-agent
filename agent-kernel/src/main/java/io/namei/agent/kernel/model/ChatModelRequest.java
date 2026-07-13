package io.namei.agent.kernel.model;

import java.util.List;

public record ChatModelRequest(List<ChatMessage> messages) {
  public ChatModelRequest {
    messages = List.copyOf(messages);
  }
}
