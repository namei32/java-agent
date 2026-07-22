package io.namei.agent.bootstrap.proactive;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 已激活本地 Runtime 安全视图使用的严格、默认禁用配置。 */
@ConfigurationProperties("agent.proactive-inspection")
public record ProactiveInspectionProperties(String mode) {
  public ProactiveInspectionProperties {
    mode = mode == null ? ProactiveInspectionMode.DISABLED.name() : mode;
    ProactiveInspectionMode.parse(mode);
  }

  public ProactiveInspectionMode toMode() {
    return ProactiveInspectionMode.parse(mode);
  }

  @Override
  public String toString() {
    return "ProactiveInspectionProperties[mode=" + mode + "]";
  }
}
