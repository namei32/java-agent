package io.namei.agent.application;

import java.util.Objects;

public record ChatCommand(String sessionId, String message) {
  public ChatCommand {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(message, "message");
  }
}
