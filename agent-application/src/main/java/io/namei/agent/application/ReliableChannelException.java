package io.namei.agent.application;

import java.util.Objects;

public final class ReliableChannelException extends RuntimeException {
  private final ReliableChannelFailure failure;

  public ReliableChannelException(ReliableChannelFailure failure) {
    super("渠道可靠性处理失败");
    this.failure = Objects.requireNonNull(failure, "failure");
  }

  public ReliableChannelFailure failure() {
    return failure;
  }
}
