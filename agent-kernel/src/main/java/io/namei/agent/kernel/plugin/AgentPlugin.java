package io.namei.agent.kernel.plugin;

/**
 * Java Plugin 的受信 classpath SPI。
 *
 * <p>实现只能声明不可变 Manifest 并提供观察型 Tap；它不能贡献 Tool、Channel 或可变运行时对象。
 */
public interface AgentPlugin {
  PluginManifest manifest();

  PluginTap tap();
}
