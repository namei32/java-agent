package io.namei.agent.bootstrap.http;

final class MemoryNotFoundException extends RuntimeException {
  MemoryNotFoundException() {
    super("记忆不存在");
  }
}
