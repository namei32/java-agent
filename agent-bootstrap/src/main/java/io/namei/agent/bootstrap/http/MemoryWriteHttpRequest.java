package io.namei.agent.bootstrap.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.namei.agent.application.MemoryWriteRequest;
import io.namei.agent.kernel.memory.MemoryType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record MemoryWriteHttpRequest(
    @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9_-]+") String requestId,
    @NotNull MemoryType type,
    @NotBlank String content,
    @Min(0) @Max(10) int emotionalWeight,
    Instant happenedAt) {
  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new InvalidMemoryRequestException();
  }

  MemoryWriteRequest toApplicationRequest() {
    try {
      return new MemoryWriteRequest(requestId, type, content, emotionalWeight, happenedAt);
    } catch (IllegalArgumentException exception) {
      throw new InvalidMemoryRequestException();
    }
  }
}
