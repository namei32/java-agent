package io.namei.agent.bootstrap.http;

public record ChatResponse(String sessionId, Message message) {
  public record Message(String role, String content) {}
}
