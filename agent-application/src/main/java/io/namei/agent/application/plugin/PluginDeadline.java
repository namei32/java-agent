package io.namei.agent.application.plugin;

@FunctionalInterface
public interface PluginDeadline {
  boolean cancel();
}
