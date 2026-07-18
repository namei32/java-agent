package io.namei.agent.application.plugin;

import java.time.Duration;

@FunctionalInterface
public interface PluginDeadlineScheduler {
  PluginDeadline schedule(Duration delay, Runnable task);
}
