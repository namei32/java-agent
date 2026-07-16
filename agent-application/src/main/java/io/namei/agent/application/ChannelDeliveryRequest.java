package io.namei.agent.application;

import java.util.Objects;

public record ChannelDeliveryRequest(String targetId, String payload) {
  public ChannelDeliveryRequest {
    targetId = identifier(targetId, "targetId", 256);
    Objects.requireNonNull(payload, "payload");
    if (payload.isEmpty() || payload.length() > 4_000) {
      throw new IllegalArgumentException("投递 Payload 无效");
    }
  }

  private static String identifier(String value, String field, int maximum) {
    Objects.requireNonNull(value, field);
    String normalized = value.strip();
    if (normalized.isEmpty()
        || normalized.length() > maximum
        || normalized.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(field + " 无效");
    }
    return normalized;
  }

  @Override
  public String toString() {
    return "ChannelDeliveryRequest[sensitiveFields=<redacted>]";
  }
}
