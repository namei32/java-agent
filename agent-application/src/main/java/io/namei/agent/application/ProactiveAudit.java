package io.namei.agent.application;

@FunctionalInterface
public interface ProactiveAudit {
  void record(ProactiveAuditEvent event);

  static ProactiveAudit disabled() {
    return ignored -> {};
  }
}
