package io.namei.agent.application;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import java.util.Objects;

/**
 * 一次成功提交后的对话结果。
 *
 * @param sessionId 结果所属内部会话
 * @param assistant 已持久化的最终助手消息，角色必须为 {@link MessageRole#ASSISTANT}
 */
public record ChatResult(String sessionId, ChatMessage assistant) {
  public ChatResult {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(assistant, "assistant");
    if (assistant.role() != MessageRole.ASSISTANT) {
      throw new IllegalArgumentException("assistant 必须使用 ASSISTANT 角色");
    }
  }
}
