package io.namei.agent.bootstrap.config;

import io.namei.agent.kernel.evidence.ConversationEvidenceMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 有界当前 Session 会话证据 Tool 的严格、默认禁用激活配置。 */
@ConfigurationProperties("agent.conversation-evidence")
public record ConversationEvidenceProperties(String mode) {
  public ConversationEvidenceProperties {
    mode = mode == null ? ConversationEvidenceMode.DISABLED.name() : mode;
    ConversationEvidenceMode.parse(mode);
  }

  ConversationEvidenceMode toMode() {
    return ConversationEvidenceMode.parse(mode);
  }

  @Override
  public String toString() {
    return "ConversationEvidenceProperties[mode=" + mode + "]";
  }
}
