package io.namei.agent.application.plugin;

@FunctionalInterface
public interface PluginTaskExecutor {
  PluginTask submit(Runnable task);
}
