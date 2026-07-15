package io.namei.agent.bootstrap.http;

final class MemoryApiPersistenceException extends RuntimeException {
  MemoryApiPersistenceException(Throwable cause) {
    super("记忆持久化失败", cause);
  }
}
