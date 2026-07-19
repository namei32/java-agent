package io.namei.agent.application;

import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.tool.ToolCall;
import java.util.Objects;

/**
 * All non-sensitive data that the future Chat boundary must provide to create one pending forget.
 */
public record MemoryForgetPendingRequest(
    String sessionId,
    long expectedNextSequence,
    String turnId,
    ToolCall call,
    PersistedTurn pendingTurn) {
  public MemoryForgetPendingRequest {
    MemoryManagementRules.scope(sessionId);
    if (expectedNextSequence < 0) {
      throw new IllegalArgumentException("预期 Session 序号不能为负数");
    }
    turnId = required(turnId, "Turn ID");
    call = Objects.requireNonNull(call, "call");
    pendingTurn = Objects.requireNonNull(pendingTurn, "pendingTurn");
  }

  @Override
  public String toString() {
    return "MemoryForgetPendingRequest[expectedNextSequence="
        + expectedNextSequence
        + ", sessionId=<redacted>, turnId=<redacted>, call=<redacted>, pendingTurn=<redacted>]";
  }

  private static String required(String value, String field) {
    String normalized = Objects.requireNonNull(value, field).strip();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return normalized;
  }
}
