package io.namei.agent.bootstrap.plugin;

@FunctionalInterface
public interface PluginRequestIdGenerator {
  String next();
}
