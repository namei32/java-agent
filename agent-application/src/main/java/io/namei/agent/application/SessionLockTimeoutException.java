package io.namei.agent.application;

public final class SessionLockTimeoutException extends RuntimeException {
  public SessionLockTimeoutException(String sessionId) {
    super("等待会话执行许可超时: " + sessionId);
  }
}
