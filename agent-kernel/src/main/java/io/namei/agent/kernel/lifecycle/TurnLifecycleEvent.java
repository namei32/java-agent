package io.namei.agent.kernel.lifecycle;

import io.namei.agent.kernel.approval.ApprovalDecisionStatus;
import io.namei.agent.kernel.tool.SideEffectExecutionState;
import io.namei.agent.kernel.tool.ToolResultStatus;
import java.util.Objects;

public record TurnLifecycleEvent(
    TurnEventType type, int iteration, String callId, String toolName, String status) {
  public TurnLifecycleEvent {
    Objects.requireNonNull(type, "type");
    callId = Objects.requireNonNullElse(callId, "").strip();
    toolName = Objects.requireNonNullElse(toolName, "").strip();
    status = Objects.requireNonNullElse(status, "").strip();
    if (iteration < 0) {
      throw new IllegalArgumentException("生命周期 iteration 不能为负数");
    }
    boolean turnEvent =
        type == TurnEventType.TURN_STARTED
            || type == TurnEventType.TURN_COMMITTING
            || type == TurnEventType.TURN_COMMITTED
            || type == TurnEventType.TURN_FAILED;
    boolean modelEvent =
        type == TurnEventType.MODEL_REQUESTED || type == TurnEventType.MODEL_COMPLETED;
    if (turnEvent && iteration != 0) {
      throw new IllegalArgumentException("Turn 事件 iteration 必须为零");
    }
    if (!turnEvent && iteration == 0) {
      throw new IllegalArgumentException("模型与工具事件 iteration 必须大于零");
    }
    if ((turnEvent || modelEvent) && (!callId.isEmpty() || !toolName.isEmpty())) {
      throw new IllegalArgumentException("Turn 与模型事件不能包含 Tool 字段");
    }
    if (!turnEvent && !modelEvent && (callId.isEmpty() || toolName.isEmpty())) {
      throw new IllegalArgumentException("Tool 事件必须包含 Call ID 和 Tool 名称");
    }
    boolean completion =
        type == TurnEventType.MODEL_COMPLETED
            || type == TurnEventType.APPROVAL_RESOLVED
            || type == TurnEventType.SIDE_EFFECT_COMPLETED
            || type == TurnEventType.TOOL_CALL_COMPLETED
            || type == TurnEventType.TURN_FAILED;
    if (completion && status.isEmpty()) {
      throw new IllegalArgumentException("完成或失败事件必须包含稳定状态");
    }
    if (!completion && !status.isEmpty()) {
      throw new IllegalArgumentException("开始事件不能包含状态");
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

  public static TurnLifecycleEvent approvalRequested(
      int iteration, String callId, String toolName) {
    return new TurnLifecycleEvent(
        TurnEventType.APPROVAL_REQUESTED, iteration, callId, toolName, "");
  }

  public static TurnLifecycleEvent approvalResolved(
      int iteration,
      String callId,
      String toolName,
      ApprovalDecisionStatus decisionStatus) {
    Objects.requireNonNull(decisionStatus, "decisionStatus");
    return new TurnLifecycleEvent(
        TurnEventType.APPROVAL_RESOLVED,
        iteration,
        callId,
        toolName,
        decisionStatus.name());
  }

  public static TurnLifecycleEvent sideEffectStarted(
      int iteration, String callId, String toolName) {
    return new TurnLifecycleEvent(
        TurnEventType.SIDE_EFFECT_STARTED, iteration, callId, toolName, "");
  }

  public static TurnLifecycleEvent sideEffectCompleted(
      int iteration, String callId, String toolName, ToolResultStatus status) {
    Objects.requireNonNull(status, "status");
    return new TurnLifecycleEvent(
        TurnEventType.SIDE_EFFECT_COMPLETED, iteration, callId, toolName, status.name());
  }

  public static TurnLifecycleEvent sideEffectCompleted(
      int iteration, String callId, String toolName, SideEffectExecutionState state) {
    Objects.requireNonNull(state, "state");
    String status =
        switch (state) {
          case SUCCEEDED -> "SUCCESS";
          case FAILED -> "ERROR";
          case UNKNOWN -> "UNKNOWN";
          case RESERVED, RUNNING ->
              throw new IllegalArgumentException("未完成状态不能用于 Side Effect 完成事件");
        };
    return new TurnLifecycleEvent(
        TurnEventType.SIDE_EFFECT_COMPLETED, iteration, callId, toolName, status);
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
