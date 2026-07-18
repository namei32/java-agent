package io.namei.agent.application;

import io.namei.agent.kernel.prompt.PromptTurnContext;
import java.util.Objects;

public record ChatCommand(String sessionId, String message, PromptTurnContext promptTurnContext) {
  public ChatCommand(String sessionId, String message) {
    this(sessionId, message, null);
  }

  public ChatCommand {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(message, "message");
  }
}
