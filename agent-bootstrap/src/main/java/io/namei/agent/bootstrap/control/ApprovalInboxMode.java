package io.namei.agent.bootstrap.control;

import java.util.Objects;

public enum ApprovalInboxMode {
  DISABLED,
  LOOPBACK;

  static ApprovalInboxMode parse(String value) {
    Objects.requireNonNull(value, "agent.approval-inbox.mode");
    try {
      return valueOf(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("未知审批收件箱模式");
    }
  }
}
