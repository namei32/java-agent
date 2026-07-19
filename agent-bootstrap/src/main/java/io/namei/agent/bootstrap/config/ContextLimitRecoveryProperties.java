package io.namei.agent.bootstrap.config;

import io.namei.agent.application.ContextLimitRecoveryMode;
import io.namei.agent.application.ContextLimitRecoveryPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Default-disabled binding for the narrow, local R10-P3 context-limit recovery path. */
@ConfigurationProperties("agent.context-limit-recovery")
public record ContextLimitRecoveryProperties(String mode) {
  public ContextLimitRecoveryProperties {
    mode = mode == null ? ContextLimitRecoveryMode.DISABLED.name() : mode;
    ContextLimitRecoveryMode.parse(mode);
  }

  ContextLimitRecoveryPolicy toPolicy() {
    return new ContextLimitRecoveryPolicy(ContextLimitRecoveryMode.parse(mode));
  }

  @Override
  public String toString() {
    return "ContextLimitRecoveryProperties[mode=" + mode + "]";
  }
}
