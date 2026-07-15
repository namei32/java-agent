package io.namei.agent.bootstrap.http;

final class InvalidMemoryRequestException extends RuntimeException {
  InvalidMemoryRequestException() {
    super("请求参数无效");
  }
}
