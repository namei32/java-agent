package io.namei.agent.adapter.mcp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * MCP 运行时的资源与安全预算。
 *
 * @param mode 启动模式
 * @param configFile 静态只读模式使用的绝对配置路径
 * @param maxServers Server 数量上限
 * @param maxToolsPerServer 单个 Server 可发布的工具上限
 * @param maxListPages 工具/资产发现允许翻页的上限
 * @param connectTimeout Server 初始化连接期限
 * @param requestTimeout 单次 MCP 请求期限
 * @param shutdownTimeout 关闭单个 Server 的等待期限
 * @param maxSchemaBytes 单个工具 Schema 字节上限
 * @param maxWireBytes 单条协议消息字节上限
 * @param maxConcurrentCallsPerServer 单个 Server 的并发调用上限
 */
public record McpSettings(
    McpMode mode,
    Path configFile,
    int maxServers,
    int maxToolsPerServer,
    int maxListPages,
    Duration connectTimeout,
    Duration requestTimeout,
    Duration shutdownTimeout,
    int maxSchemaBytes,
    int maxWireBytes,
    int maxConcurrentCallsPerServer) {

  public McpSettings {
    Objects.requireNonNull(mode, "mode");
    requireRange(maxServers, 1, 16);
    requireRange(maxToolsPerServer, 1, 128);
    requireRange(maxListPages, 1, 32);
    requirePositive(connectTimeout);
    requirePositive(requestTimeout);
    requirePositive(shutdownTimeout);
    if (shutdownTimeout.compareTo(Duration.ofSeconds(10)) > 0) {
      throw invalid();
    }
    requireRange(maxSchemaBytes, 1, 1_048_576);
    requireRange(maxWireBytes, 1, 4_194_304);
    requireRange(maxConcurrentCallsPerServer, 1, 8);
    if (mode == McpMode.STATIC_READ_ONLY) {
      Objects.requireNonNull(configFile, "configFile");
      if (!configFile.isAbsolute()) {
        throw invalid();
      }
      configFile = configFile.normalize();
    } else {
      configFile = null;
    }
  }

  public static McpSettings disabled() {
    return new McpSettings(
        McpMode.DISABLED,
        null,
        4,
        32,
        8,
        Duration.ofSeconds(5),
        Duration.ofSeconds(4),
        Duration.ofSeconds(2),
        65_536,
        1_048_576,
        1);
  }

  public static McpSettings staticReadOnlyDefaults(Path configFile) {
    return new McpSettings(
        McpMode.STATIC_READ_ONLY,
        configFile,
        4,
        32,
        8,
        Duration.ofSeconds(5),
        Duration.ofSeconds(4),
        Duration.ofSeconds(2),
        65_536,
        1_048_576,
        1);
  }

  @Override
  public String toString() {
    return "McpSettings[mode=" + mode + "]";
  }

  private static void requirePositive(Duration value) {
    Objects.requireNonNull(value, "duration");
    if (value.isZero() || value.isNegative()) {
      throw invalid();
    }
  }

  private static void requireRange(int value, int minimum, int maximum) {
    if (value < minimum || value > maximum) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("MCP 安全配置无效");
  }
}
