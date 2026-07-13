package io.namei.agent.bootstrap.http;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChatRequest(
    @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9_-]+") String sessionId,
    @NotBlank @Size(max = 32_000) String message) {
  public ChatRequest {
    if (message != null) {
      message = message.strip();
    }
  }
}
