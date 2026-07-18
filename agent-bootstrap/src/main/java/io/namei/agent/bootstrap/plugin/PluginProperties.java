package io.namei.agent.bootstrap.plugin;

import io.namei.agent.kernel.plugin.PluginCapability;
import io.namei.agent.kernel.plugin.PluginId;
import io.namei.agent.kernel.plugin.PluginKind;
import io.namei.agent.kernel.plugin.PluginManifest;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.plugins")
public final class PluginProperties {
  private final PluginMode mode;
  private final Duration tapTimeout;
  private final Duration shutdownTimeout;
  private final int maxFrameBytes;
  private final List<PluginId> javaServiceIds;
  private final List<External> external;

  @ConstructorBinding
  public PluginProperties(
      @DefaultValue("DISABLED") String mode,
      @DefaultValue("500ms") Duration tapTimeout,
      @DefaultValue("1s") Duration shutdownTimeout,
      @DefaultValue("65536") Integer maxFrameBytes,
      List<String> javaServiceIds,
      List<External> external) {
    this.mode = parseMode(mode);
    this.tapTimeout = tapTimeout == null ? Duration.ofMillis(500) : tapTimeout;
    this.shutdownTimeout = shutdownTimeout == null ? Duration.ofSeconds(1) : shutdownTimeout;
    this.maxFrameBytes = maxFrameBytes == null ? 65_536 : maxFrameBytes;
    new ExternalStdioBridgeLimits(this.maxFrameBytes, this.tapTimeout, this.shutdownTimeout);
    this.javaServiceIds =
        List.copyOf(
            (javaServiceIds == null ? List.<String>of() : javaServiceIds)
                .stream().map(PluginId::parse).toList());
    this.external = List.copyOf(external == null ? List.of() : external);
    if (new HashSet<>(this.javaServiceIds).size() != this.javaServiceIds.size()) {
      throw new IllegalArgumentException("agent.plugins.java-service-ids 不能重复");
    }
    if (this.mode == PluginMode.JAVA_SERVICE && !this.external.isEmpty()) {
      throw new IllegalArgumentException("JAVA_SERVICE 不允许 external Plugin 配置");
    }
    if (this.mode == PluginMode.EXTERNAL_STDIO && !this.javaServiceIds.isEmpty()) {
      throw new IllegalArgumentException("EXTERNAL_STDIO 不允许 java-service-ids 配置");
    }
    if (this.mode == PluginMode.EXTERNAL_STDIO && this.external.isEmpty()) {
      throw new IllegalArgumentException("EXTERNAL_STDIO 至少需要一个 external Plugin");
    }
    if (new HashSet<>(this.external.stream().map(External::id).toList()).size()
        != this.external.size()) {
      throw new IllegalArgumentException("agent.plugins.external ID 不能重复");
    }
  }

  public PluginMode mode() {
    return mode;
  }

  public Duration tapTimeout() {
    return tapTimeout;
  }

  public Duration shutdownTimeout() {
    return shutdownTimeout;
  }

  public int maxFrameBytes() {
    return maxFrameBytes;
  }

  public List<PluginId> javaServiceIds() {
    return javaServiceIds;
  }

  public List<External> external() {
    return external;
  }

  public ExternalStdioBridgeLimits bridgeLimits() {
    return new ExternalStdioBridgeLimits(maxFrameBytes, tapTimeout, shutdownTimeout);
  }

  @Override
  public String toString() {
    return "PluginProperties[mode=" + mode + ", plugins=<configured>]";
  }

  private static PluginMode parseMode(String value) {
    if (value == null) {
      return PluginMode.DISABLED;
    }
    try {
      PluginMode parsed = PluginMode.valueOf(value);
      if (!parsed.name().equals(value)) {
        throw new IllegalArgumentException();
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(
          "agent.plugins.mode 仅接受 DISABLED、JAVA_SERVICE、EXTERNAL_STDIO");
    }
  }

  public record External(
      String id, String version, List<String> command, List<String> capabilities) {
    public External {
      PluginId.parse(id);
      if (version == null || version.isBlank() || version.length() > 64) {
        throw new IllegalArgumentException("external Plugin version 非法");
      }
      command = List.copyOf(Objects.requireNonNull(command, "command"));
      capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
      if (capabilities.isEmpty()) {
        throw new IllegalArgumentException("external Plugin 至少需要一个 Tap capability");
      }
    }

    PluginManifest manifest() {
      return new PluginManifest(
          1,
          PluginId.parse(id),
          version,
          1,
          PluginKind.EXTERNAL_STDIO,
          capabilities.stream().map(PluginCapability::valueOf).toList());
    }

    ExternalStdioCommand stdioCommand() {
      return new ExternalStdioCommand(command);
    }
  }
}
