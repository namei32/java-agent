package io.namei.agent.kernel.model;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 表示已经完成、可以原子写入会话历史的一轮对话。
 *
 * @param user 用户消息，角色必须为 {@link MessageRole#USER}
 * @param userAt 用户消息的记录时间
 * @param assistant 助手最终消息，角色必须为 {@link MessageRole#ASSISTANT}
 * @param assistantAt 助手消息的记录时间
 */
public record PersistedTurn(
    ChatMessage user, OffsetDateTime userAt, ChatMessage assistant, OffsetDateTime assistantAt) {
  public PersistedTurn {
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(userAt, "userAt");
    Objects.requireNonNull(assistant, "assistant");
    Objects.requireNonNull(assistantAt, "assistantAt");
    if (user.role() != MessageRole.USER || assistant.role() != MessageRole.ASSISTANT) {
      throw new IllegalArgumentException("持久化轮次必须由 user 和 assistant 组成");
    }
  }
}
