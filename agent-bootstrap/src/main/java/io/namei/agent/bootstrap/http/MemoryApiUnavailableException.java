package io.namei.agent.bootstrap.http;

final class MemoryApiUnavailableException extends RuntimeException {
  MemoryApiUnavailableException() {
    super("记忆功能不可用");
  }
}
