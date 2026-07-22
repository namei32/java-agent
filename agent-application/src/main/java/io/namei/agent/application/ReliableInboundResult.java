package io.namei.agent.application;

import java.util.Objects;

/**
 * 可靠入站事件完成持久协调后的结果投影。
 *
 * @param status 调度、去重、控制或反馈状态
 * @param turnId 关联的内部 Turn ID；不适用时为空字符串
 * @param nextSequence 外部渠道下一次应读取的序号
 */
public record ReliableInboundResult(Status status, String turnId, long nextSequence) {
  /** 定义入站协调器对调用方稳定暴露的处理结果。 */
  public enum Status {
    TURN_SCHEDULED,
    IN_PROGRESS,
    ALREADY_TERMINAL,
    EXECUTION_UNKNOWN,
    IGNORED_RECORDED,
    CONTROL_APPLIED,
    FEEDBACK_QUEUED
  }

  public ReliableInboundResult {
    Objects.requireNonNull(status, "status");
    turnId = turnId == null ? "" : turnId;
    if (nextSequence < 0) {
      throw new IllegalArgumentException("nextSequence 不能为负数");
    }
  }

  @Override
  public String toString() {
    return "ReliableInboundResult[status=" + status + ", sensitiveFields=<redacted>]";
  }
}
