package io.namei.agent.kernel.model;

import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolResultStatus;
import java.util.Objects;

/**
 * 表示一次工具调用的执行结果消息，用于把结果反馈给模型继续推理。
 *
 * @param toolCallId 对应模型工具调用的唯一标识
 * @param toolName 被调用的工具名称
 * @param status 工具执行状态
 * @param content 提供给模型的非空结果正文，不应包含未经脱敏的内部异常
 */
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
