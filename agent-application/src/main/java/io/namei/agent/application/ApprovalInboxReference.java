package io.namei.agent.application;

import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/** 本地 Approval Inbox 暴露的、不透明且独立生成的引用。 */
public record ApprovalInboxReference(String value) {
  private static final Pattern BASE64_URL_128_BIT = Pattern.compile("[A-Za-z0-9_-]{22}");

  public ApprovalInboxReference {
    value = Objects.requireNonNull(value, "approvalRef").strip();
    if (!BASE64_URL_128_BIT.matcher(value).matches()) {
      throw new IllegalArgumentException("审批引用格式无效");
    }
    try {
      if (Base64.getUrlDecoder().decode(value).length != 16) {
        throw new IllegalArgumentException("审批引用长度无效");
      }
    } catch (IllegalArgumentException invalidReference) {
      throw new IllegalArgumentException("审批引用格式无效");
    }
  }

  public static ApprovalInboxReference of(String value) {
    return new ApprovalInboxReference(value);
  }

  @Override
  public String toString() {
    return "ApprovalInboxReference[value=<redacted>]";
  }
}
