package io.namei.agent.bootstrap.config;

import io.namei.agent.kernel.memory.MemoryRuntimeMode;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import org.springframework.core.env.Environment;

final class JavaNativeMemoryAccessGuard {
  private final AgentProperties properties;
  private final Environment environment;

  JavaNativeMemoryAccessGuard(AgentProperties properties, Environment environment) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.environment = Objects.requireNonNull(environment, "environment");
  }

  void validate() {
    if (properties.memory().mode() != MemoryRuntimeMode.JAVA_NATIVE) {
      return;
    }
    String configuredAddress = environment.getProperty("server.address");
    if (!isLoopback(configuredAddress)) {
      throw new IllegalStateException("JAVA_NATIVE 记忆只允许 Loopback 监听");
    }
  }

  private static boolean isLoopback(String configuredAddress) {
    if (configuredAddress == null || configuredAddress.strip().isBlank()) {
      return false;
    }
    String address = configuredAddress.strip();
    if (address.startsWith("[") && address.endsWith("]")) {
      address = address.substring(1, address.length() - 1);
    }
    try {
      return InetAddress.getByName(address).isLoopbackAddress();
    } catch (UnknownHostException exception) {
      return false;
    }
  }
}
