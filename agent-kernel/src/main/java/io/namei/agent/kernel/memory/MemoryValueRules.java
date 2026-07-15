package io.namei.agent.kernel.memory;

import java.util.Objects;
import java.util.regex.Pattern;

final class MemoryValueRules {
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]+");

  private MemoryValueRules() {}

  static String required(String value, String field, int maxLength) {
    Objects.requireNonNull(value, field);
    String normalized = value.strip();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException(field + " 超过长度上限");
    }
    return normalized;
  }

  static String content(String value) {
    Objects.requireNonNull(value, "content");
    if (value.strip().isBlank()) {
      throw new IllegalArgumentException("Memory Content 不能为空");
    }
    if (value.length() > 4000) {
      throw new IllegalArgumentException("Memory Content 超过长度上限");
    }
    return value;
  }

  static String sha256(String value, String field) {
    Objects.requireNonNull(value, field);
    if (!SHA_256.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " 必须为 SHA-256");
    }
    return value;
  }

  static String requestId(String value) {
    return safeIdentifier(value, "Request ID", 128);
  }

  static String itemId(String value) {
    return safeIdentifier(value, "Memory Item ID", 128);
  }

  private static String safeIdentifier(String value, String field, int maxLength) {
    String normalized = required(value, field, maxLength);
    if (!SAFE_IDENTIFIER.matcher(normalized).matches()) {
      throw new IllegalArgumentException(field + " 格式无效");
    }
    return normalized;
  }
}
