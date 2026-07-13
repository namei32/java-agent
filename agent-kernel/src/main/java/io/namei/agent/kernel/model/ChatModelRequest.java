package io.namei.agent.kernel.model;

import io.namei.agent.kernel.tool.ToolDefinition;
import java.util.List;
import java.util.Objects;

public final class ChatModelRequest {
  private final List<ModelMessage> messages;
  private final List<ToolDefinition> tools;

  public ChatModelRequest(List<? extends ModelMessage> messages) {
    this(messages, List.of());
  }

  public ChatModelRequest(List<? extends ModelMessage> messages, List<ToolDefinition> tools) {
    this.messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    this.tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
    if (this.messages.isEmpty()) {
      throw new IllegalArgumentException("模型消息不能为空");
    }
  }

  public List<ModelMessage> messages() {
    return messages;
  }

  public List<ToolDefinition> tools() {
    return tools;
  }
}
