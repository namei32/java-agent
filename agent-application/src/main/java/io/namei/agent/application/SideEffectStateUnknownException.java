package io.namei.agent.application;

public final class SideEffectStateUnknownException extends RuntimeException {
  public SideEffectStateUnknownException() {
    super("副作用执行状态未知");
  }
}
