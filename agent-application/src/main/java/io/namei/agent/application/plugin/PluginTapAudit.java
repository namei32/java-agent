package io.namei.agent.application.plugin;

@FunctionalInterface
public interface PluginTapAudit {
  void record(PluginTapAuditEvent event);

  static PluginTapAudit disabled() {
    return event -> {};
  }
}
