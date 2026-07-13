package io.namei.agent.kernel.model;

import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolResultStatus;
import java.util.Objects;

public record ToolResultMessage(
    String toolCallId, String toolName, ToolResultStatus status, String content)
    implements ModelMessage {
  public ToolResultMessage(ToolCall call, ToolResult result) {
    this(call.id(), call.name(), result.status(), result.content());
  }

  public ToolResultMessage {
    Objects.requireNonNull(toolCallId, "toolCallId");
    Objects.requireNonNull(toolName, "toolName");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(content, "content");
    toolCallId = toolCallId.strip();
    toolName = toolName.strip();
    content = content.strip();
    if (toolCallId.isBlank() || toolName.isBlank() || content.isBlank()) {
      throw new IllegalArgumentException("Tool Result 字段不能为空");
    }
  }

  @Override
  public MessageRole role() {
    return MessageRole.TOOL;
  }
}
