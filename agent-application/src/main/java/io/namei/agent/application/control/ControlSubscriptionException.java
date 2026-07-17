package io.namei.agent.application.control;

import java.util.Objects;

public final class ControlSubscriptionException extends RuntimeException {
  private final Reason reason;

  public ControlSubscriptionException(Reason reason) {
    super(message(reason));
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public Reason reason() {
    return reason;
  }

  private static String message(Reason reason) {
    return switch (Objects.requireNonNull(reason, "reason")) {
      case TURN_NOT_FOUND -> "控制面 Turn 不存在";
      case ALREADY_TERMINAL -> "控制面 Turn 已结束";
      case CAPACITY_EXCEEDED -> "控制面订阅容量已满";
      case SHUTTING_DOWN -> "控制面事件 Hub 正在关闭";
    };
  }

  public enum Reason {
    TURN_NOT_FOUND,
    ALREADY_TERMINAL,
    CAPACITY_EXCEEDED,
    SHUTTING_DOWN
  }
}
