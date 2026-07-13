package io.namei.agent.kernel.lifecycle;

import io.namei.agent.kernel.tool.ToolResultStatus;
import java.util.Objects;

public record TurnLifecycleEvent(
    TurnEventType type, int iteration, String callId, String toolName, String status) {
  public TurnLifecycleEvent {
    Objects.requireNonNull(type, "type");
    callId = Objects.requireNonNullElse(callId, "");
    toolName = Objects.requireNonNullElse(toolName, "");
    status = Objects.requireNonNullElse(status, "");
    if (iteration < 0) {
      throw new IllegalArgumentException("生命周期 iteration 不能为负数");
    }
  }

  public static TurnLifecycleEvent turnStarted() {
    return turn(TurnEventType.TURN_STARTED, "");
  }

  public static TurnLifecycleEvent modelRequested(int iteration) {
    return new TurnLifecycleEvent(TurnEventType.MODEL_REQUESTED, iteration, "", "", "");
  }

  public static TurnLifecycleEvent modelCompleted(int iteration, String status) {
    return new TurnLifecycleEvent(TurnEventType.MODEL_COMPLETED, iteration, "", "", status);
  }

  public static TurnLifecycleEvent toolStarted(int iteration, String callId, String toolName) {
    return new TurnLifecycleEvent(TurnEventType.TOOL_CALL_STARTED, iteration, callId, toolName, "");
  }

  public static TurnLifecycleEvent toolCompleted(
      int iteration, String callId, String toolName, ToolResultStatus status) {
    return new TurnLifecycleEvent(
        TurnEventType.TOOL_CALL_COMPLETED, iteration, callId, toolName, status.name());
  }

  public static TurnLifecycleEvent turnCommitting() {
    return turn(TurnEventType.TURN_COMMITTING, "");
  }

  public static TurnLifecycleEvent turnCommitted() {
    return turn(TurnEventType.TURN_COMMITTED, "");
  }

  public static TurnLifecycleEvent turnFailed(String status) {
    return turn(TurnEventType.TURN_FAILED, status);
  }

  private static TurnLifecycleEvent turn(TurnEventType type, String status) {
    return new TurnLifecycleEvent(type, 0, "", "", status);
  }
}
