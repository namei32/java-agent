package io.namei.agent.kernel.channel.reliability;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

final class ChannelReliabilityValues {
  static final int MAX_PARTS = 16;
  static final int MAX_PART_UNITS = 4_000;
  static final int MAX_START_ATTEMPTS = 3;
  static final int MAX_DELIVERY_ATTEMPTS = 2;

  private static final Pattern CHANNEL = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern TRUSTED_INSTANCE_KEY =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
  private static final Pattern SAFE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final Pattern ALGORITHM = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
  private static final Pattern OWNER = Pattern.compile("[0-9a-f]{32}");

  private ChannelReliabilityValues() {}

  static String channel(String value) {
    String normalized = required(value, "channel", 32);
    if (!CHANNEL.matcher(normalized).matches()) {
      throw new IllegalArgumentException("channel 格式无效");
    }
    return normalized;
  }

  static String trustedInstanceKey(String value) {
    String normalized = required(value, "trustedInstanceKey", 128);
    if (!TRUSTED_INSTANCE_KEY.matcher(normalized).matches()) {
      throw new IllegalArgumentException("渠道实例键格式无效");
    }
    return normalized;
  }

  static String hash(String value, String field) {
    Objects.requireNonNull(value, field);
    if (!HASH.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " 必须为 SHA-256");
    }
    return value;
  }

  static String identifier(String value, String field, int maximum) {
    String normalized = required(value, field, maximum);
    if (normalized.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(field + " 不能包含控制字符");
    }
    return normalized;
  }

  static String decisionCode(String value, boolean required) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      if (required) {
        throw new IllegalArgumentException("决策码不能为空");
      }
      return "";
    }
    if (!SAFE_CODE.matcher(normalized).matches()) {
      throw new IllegalArgumentException("决策码格式无效");
    }
    return normalized;
  }

  static String algorithm(String value) {
    String normalized = required(value, "chunkAlgorithm", 64);
    if (!ALGORITHM.matcher(normalized).matches()) {
      throw new IllegalArgumentException("分片算法格式无效");
    }
    return normalized;
  }

  static String ownerId(String value) {
    Objects.requireNonNull(value, "ownerId");
    if (!OWNER.matcher(value).matches()) {
      throw new IllegalArgumentException("Owner ID 格式无效");
    }
    return value;
  }

  static String payload(String value) {
    Objects.requireNonNull(value, "payload");
    if (value.isEmpty()) {
      throw new IllegalArgumentException("投递分片不能为空");
    }
    if (value.length() > MAX_PART_UNITS) {
      throw new IllegalArgumentException("投递分片超过长度上限");
    }
    return value;
  }

  static long externalSequence(long value) {
    if (value < 0 || value == Long.MAX_VALUE) {
      throw new IllegalArgumentException("外部序号无法安全推进");
    }
    return value;
  }

  static int nonNegative(int value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " 不能为负数");
    }
    return value;
  }

  static long nonNegative(long value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " 不能为负数");
    }
    return value;
  }

  static Instant instant(Instant value, String field) {
    return Objects.requireNonNull(value, field);
  }

  static String optionalIdentifier(String value, String field, int maximum) {
    return value == null ? null : identifier(value, field, maximum);
  }

  private static String required(String value, String field, int maximum) {
    Objects.requireNonNull(value, field);
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    if (normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " 超过长度上限");
    }
    return normalized;
  }
}
