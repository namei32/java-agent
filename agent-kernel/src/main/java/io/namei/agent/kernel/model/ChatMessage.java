package io.namei.agent.kernel.model;

import java.util.Objects;

/**
 * 表示发送给模型或由模型返回的一条纯文本消息。
 *
 * @param role 消息角色；工具结果不能使用该类型，必须使用 {@link ToolResultMessage}
 * @param content 去除首尾空白后的消息正文，不能为空
 */
public record ChatMessage(MessageRole role, String content) implements ModelMessage {
  public ChatMessage {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(content, "content");
    content = content.strip();
    if (content.isBlank()) {
      throw new IllegalArgumentException("消息内容不能为空");
    }
    if (role == MessageRole.TOOL) {
      throw new IllegalArgumentException("Tool 消息必须使用 ToolResultMessage");
    }
  }
}
