package io.namei.agent.application;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.tool.ToolCall;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Internal, single-turn inputs already authenticated by {@link ChatService}. */
record MemoryForgetPendingTurnContext(
    String sessionId,
    long expectedNextSequence,
    String turnId,
    ChatMessage user,
    OffsetDateTime userAt,
    Clock clock) {
  MemoryForgetPendingTurnContext {
    MemoryManagementRules.scope(sessionId);
    if (expectedNextSequence < 0) {
      throw new IllegalArgumentException("预期 Session 序号不能为负数");
    }
    turnId = required(turnId, "Turn ID");
    user = Objects.requireNonNull(user, "user");
    if (user.role() != MessageRole.USER) {
      throw new IllegalArgumentException("Pending Producer 只接受 User 消息");
    }
    userAt = Objects.requireNonNull(userAt, "userAt");
    clock = Objects.requireNonNull(clock, "clock");
  }

  MemoryForgetPendingRequest request(ToolCall call) {
    Objects.requireNonNull(call, "call");
    return new MemoryForgetPendingRequest(
        sessionId,
        expectedNextSequence,
        turnId,
        call,
        new PersistedTurn(
            user,
            userAt,
            new ChatMessage(
                MessageRole.ASSISTANT, MemoryForgetPendingToolset.pendingAssistantProjection()),
            OffsetDateTime.now(clock)));
  }

  @Override
  public String toString() {
    return "MemoryForgetPendingTurnContext[expectedNextSequence="
        + expectedNextSequence
        + ", sessionId=<redacted>, turnId=<redacted>, user=<redacted>]";
  }

  private static String required(String value, String field) {
    String normalized = Objects.requireNonNull(value, field).strip();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return normalized;
  }
}
