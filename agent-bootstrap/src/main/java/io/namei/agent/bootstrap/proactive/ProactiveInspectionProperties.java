package io.namei.agent.bootstrap.proactive;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Strict, default-disabled activation for a safe view over an already active local runtime. */
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
