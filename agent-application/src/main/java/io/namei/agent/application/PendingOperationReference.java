package io.namei.agent.application;

import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/** Opaque operation reference. It is distinct from approval, turn, and session identifiers. */
public record PendingOperationReference(String value) {
  private static final Pattern BASE64_URL_128_BIT = Pattern.compile("[A-Za-z0-9_-]{22}");

  public PendingOperationReference {
    value = Objects.requireNonNull(value, "operationRef").strip();
    if (!BASE64_URL_128_BIT.matcher(value).matches()) {
      throw new IllegalArgumentException("待审批操作引用格式无效");
    }
    try {
      if (Base64.getUrlDecoder().decode(value).length != 16) {
        throw new IllegalArgumentException("待审批操作引用长度无效");
      }
    } catch (IllegalArgumentException invalidReference) {
      throw new IllegalArgumentException("待审批操作引用格式无效");
    }
  }

  public static PendingOperationReference of(String value) {
    return new PendingOperationReference(value);
  }

  @Override
  public String toString() {
    return "PendingOperationReference[value=<redacted>]";
  }
}
