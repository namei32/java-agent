package io.namei.agent.kernel.evidence;

import java.util.Objects;
import java.util.Optional;

/**
 * 只读会话证据 Tool 向模型暴露的不透明 Session 本地消息引用。
 *
 * <p>该引用有意仅包含序号，绝不能编码 Session Key、Route、Sender、数据库标识符或 Python-compatible Source 引用。
 */
public record ConversationEvidenceReference(long sequence) {
  private static final String PREFIX = "msg-v1:";

  public ConversationEvidenceReference {
    if (sequence < 0) {
      throw new IllegalArgumentException("会话证据序号不能为负数");
    }
  }

  public String externalId() {
    return PREFIX + sequence;
  }

  public static Optional<ConversationEvidenceReference> parse(String raw) {
    if (raw == null || !raw.startsWith(PREFIX)) {
      return Optional.empty();
    }
    String numeric = raw.substring(PREFIX.length());
    if (numeric.isEmpty() || (numeric.length() > 1 && numeric.charAt(0) == '0')) {
      return Optional.empty();
    }
    for (int index = 0; index < numeric.length(); index++) {
      char character = numeric.charAt(index);
      if (character < '0' || character > '9') {
        return Optional.empty();
      }
    }
    try {
      return Optional.of(new ConversationEvidenceReference(Long.parseLong(numeric)));
    } catch (NumberFormatException invalid) {
      return Optional.empty();
    }
  }

  public static ConversationEvidenceReference require(String raw) {
    return parse(Objects.requireNonNull(raw, "raw"))
        .orElseThrow(() -> new IllegalArgumentException("会话证据引用无效"));
  }
}
