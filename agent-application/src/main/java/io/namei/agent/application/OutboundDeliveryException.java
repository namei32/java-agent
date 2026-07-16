package io.namei.agent.application;

import java.util.Objects;

public final class OutboundDeliveryException extends RuntimeException {
  private final Reason reason;

  public OutboundDeliveryException(Reason reason) {
    super(message(reason));
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public Reason reason() {
    return reason;
  }

  private static String message(Reason reason) {
    Objects.requireNonNull(reason, "reason");
    return switch (reason) {
      case CHANNEL_DISCONNECTED -> "出站渠道已断开";
      case BACKPRESSURE_EXCEEDED -> "出站缓冲超过背压期限";
      case INTERRUPTED -> "出站投递被中断";
      case INVALID_MESSAGE -> "出站消息违反顺序契约";
      case SHUTDOWN -> "出站渠道正在关闭";
      case DURABLE_COMMIT_FAILED -> "出站终态持久化失败";
      case TERMINAL_CONFLICT -> "出站终态与已持久化结果冲突";
    };
  }

  public enum Reason {
    CHANNEL_DISCONNECTED,
    BACKPRESSURE_EXCEEDED,
    INTERRUPTED,
    INVALID_MESSAGE,
    SHUTDOWN,
    DURABLE_COMMIT_FAILED,
    TERMINAL_CONFLICT
  }
}
