package io.namei.agent.application;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

public sealed interface ChannelDeliveryResult
    permits ChannelDeliveryResult.Confirmed,
        ChannelDeliveryResult.Retryable,
        ChannelDeliveryResult.Permanent,
        ChannelDeliveryResult.Unknown {
  Pattern SAFE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

  record Confirmed(String remoteMessageId) implements ChannelDeliveryResult {
    public Confirmed {
      remoteMessageId = identifier(remoteMessageId, "remoteMessageId", 128);
    }
  }

  record Retryable(Duration retryAfter, String code) implements ChannelDeliveryResult {
    public Retryable {
      Objects.requireNonNull(retryAfter, "retryAfter");
      code = requireCode(code);
    }
  }

  record Permanent(String code) implements ChannelDeliveryResult {
    public Permanent {
      code = requireCode(code);
    }
  }

  record Unknown(String code) implements ChannelDeliveryResult {
    public Unknown {
      code = requireCode(code);
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

  private static String requireCode(String value) {
    Objects.requireNonNull(value, "code");
    String normalized = value.strip();
    if (!SAFE_CODE.matcher(normalized).matches()) {
      throw new IllegalArgumentException("投递结果码无效");
    }
    return normalized;
  }
}
