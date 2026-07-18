package io.namei.agent.application.plugin;

public interface PluginTask {
  boolean cancel();

  boolean isDone();
}
