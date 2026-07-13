package io.namei.agent.kernel.model;

public sealed interface ModelMessage
    permits ChatMessage, AssistantToolCallMessage, ToolResultMessage {
  MessageRole role();

  String content();
}
