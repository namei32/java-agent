package io.namei.agent.bootstrap.plugin;

import java.time.Duration;
import java.util.Objects;

public record ExternalStdioBridgeLimits(
    int maxFrameBytes, Duration requestTimeout, Duration shutdownTimeout) {
  public ExternalStdioBridgeLimits {
    if (maxFrameBytes < 128 || maxFrameBytes > 1_048_576) {
      throw new IllegalArgumentException("External stdio 单帧必须在 128..1048576 bytes");
    }
    requestTimeout = bounded(requestTimeout, "requestTimeout");
    shutdownTimeout = bounded(shutdownTimeout, "shutdownTimeout");
  }

  private static Duration bounded(Duration value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofSeconds(10)) > 0) {
      throw new IllegalArgumentException(field + " 必须在 0..10s");
    }
    return value;
  }
}
