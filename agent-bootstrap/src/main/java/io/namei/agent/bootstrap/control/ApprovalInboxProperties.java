package io.namei.agent.bootstrap.control;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.approval-inbox")
public final class ApprovalInboxProperties {
  private final ApprovalInboxMode mode;

  @ConstructorBinding
  public ApprovalInboxProperties(@DefaultValue("DISABLED") String mode) {
    this.mode = ApprovalInboxMode.parse(mode);
  }

  public ApprovalInboxMode mode() {
    return mode;
  }

  @Override
  public String toString() {
    return "ApprovalInboxProperties[mode=" + mode + "]";
  }
}
