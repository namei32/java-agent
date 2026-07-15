package io.namei.agent.bootstrap.config;

import io.namei.agent.adapter.mcp.McpMode;
import io.namei.agent.adapter.mcp.McpSettings;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent.mcp")
public record McpProperties(
    McpMode mode,
    Path configFile,
    Integer maxServers,
    Integer maxToolsPerServer,
    Integer maxListPages,
    Duration connectTimeout,
    Duration requestTimeout,
    Duration shutdownTimeout,
    Integer maxSchemaBytes,
    Integer maxWireBytes,
    Integer maxConcurrentCallsPerServer) {

  public McpProperties {
    mode = mode == null ? McpMode.DISABLED : mode;
    maxServers = defaultValue(maxServers, 4);
    maxToolsPerServer = defaultValue(maxToolsPerServer, 32);
    maxListPages = defaultValue(maxListPages, 8);
    connectTimeout = defaultValue(connectTimeout, Duration.ofSeconds(5));
    requestTimeout = defaultValue(requestTimeout, Duration.ofSeconds(4));
    shutdownTimeout = defaultValue(shutdownTimeout, Duration.ofSeconds(2));
    maxSchemaBytes = defaultValue(maxSchemaBytes, 65_536);
    maxWireBytes = defaultValue(maxWireBytes, 1_048_576);
    maxConcurrentCallsPerServer = defaultValue(maxConcurrentCallsPerServer, 1);
  }

  McpSettings toSettings() {
    if (mode == McpMode.STATIC_READ_ONLY && configFile == null) {
      throw new IllegalArgumentException("agent.mcp.config-file 必填");
    }
    return new McpSettings(
        mode,
        configFile,
        maxServers,
        maxToolsPerServer,
        maxListPages,
        connectTimeout,
        requestTimeout,
        shutdownTimeout,
        maxSchemaBytes,
        maxWireBytes,
        maxConcurrentCallsPerServer);
  }

  @Override
  public String toString() {
    return "McpProperties[mode=" + mode + "]";
  }

  private static int defaultValue(Integer value, int fallback) {
    return value == null ? fallback : value;
  }

  private static Duration defaultValue(Duration value, Duration fallback) {
    return value == null ? fallback : value;
  }
}
