package io.namei.agent.kernel.evidence;

/** 当前 Session 会话证据使用的严格、默认禁用 Bootstrap 模式。 */
public enum ConversationEvidenceMode {
  DISABLED,
  CURRENT_SESSION_READ_ONLY;

  public static ConversationEvidenceMode parse(String value) {
    if (value == null || value.isBlank()) {
      return DISABLED;
    }
    try {
      return valueOf(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("agent.conversation-evidence.mode 无效", invalid);
    }
  }
}
