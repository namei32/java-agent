package io.namei.agent.bootstrap.control;

@FunctionalInterface
public interface ControlPlaneAuditSink {
  void accept(ControlAuditEvent event);

  static ControlPlaneAuditSink disabled() {
    return event -> {};
  }
}
