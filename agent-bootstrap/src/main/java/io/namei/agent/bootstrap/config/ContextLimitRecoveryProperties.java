package io.namei.agent.bootstrap.config;

import io.namei.agent.application.ContextLimitRecoveryMode;
import io.namei.agent.application.ContextLimitRecoveryPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 狭窄本地 R10-P3 上下文超限恢复路径的默认禁用绑定。 */
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
