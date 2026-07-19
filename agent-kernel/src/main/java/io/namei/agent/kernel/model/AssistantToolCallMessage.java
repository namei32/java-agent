package io.namei.agent.kernel.model;

import io.namei.agent.kernel.tool.ToolCall;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AssistantToolCallMessage(
    String content, List<ToolCall> toolCalls, Optional<ProviderReasoning> reasoning)
    implements ModelMessage {
  public AssistantToolCallMessage(String content, List<ToolCall> toolCalls) {
    this(content, toolCalls, Optional.empty());
  }

  public AssistantToolCallMessage(
      String content, List<ToolCall> toolCalls, ProviderReasoning reasoning) {
    this(content, toolCalls, Optional.ofNullable(reasoning));
  }

  public AssistantToolCallMessage {
    content = content == null ? "" : content.strip();
    toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
    reasoning = Objects.requireNonNull(reasoning, "reasoning");
    if (toolCalls.isEmpty()) {
      throw new IllegalArgumentException("Assistant Tool Call 不能为空");
    }
    var identifiers = new HashSet<String>();
    for (ToolCall call : toolCalls) {
      if (!identifiers.add(call.id())) {
        throw new IllegalArgumentException("Tool Call ID 重复: " + call.id());
      }
    }
  }

  @Override
  public MessageRole role() {
    return MessageRole.ASSISTANT;
  }
}
