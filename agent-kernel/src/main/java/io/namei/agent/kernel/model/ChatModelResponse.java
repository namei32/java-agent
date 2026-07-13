package io.namei.agent.kernel.model;

import java.util.Objects;

public record ChatModelResponse(String content) {
  public ChatModelResponse {
    Objects.requireNonNull(content, "content");
  }
}
