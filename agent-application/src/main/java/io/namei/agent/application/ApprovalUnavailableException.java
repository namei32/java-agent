package io.namei.agent.application;

public final class ApprovalUnavailableException extends RuntimeException {
  public ApprovalUnavailableException() {
    super("工具审批当前不可用");
  }
}
