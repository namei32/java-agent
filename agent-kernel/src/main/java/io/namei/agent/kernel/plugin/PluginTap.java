package io.namei.agent.kernel.plugin;

@FunctionalInterface
public interface PluginTap {
  void accept(PluginTapEvent event) throws Exception;
}
