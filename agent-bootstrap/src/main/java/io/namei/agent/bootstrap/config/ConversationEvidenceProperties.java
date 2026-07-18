package io.namei.agent.bootstrap.config;

import io.namei.agent.kernel.evidence.ConversationEvidenceMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Strict, default-disabled activation for bounded current-session conversation evidence tools. */
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
