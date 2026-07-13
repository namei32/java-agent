package io.namei.agent.bootstrap.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

record ConfigurationInputs(
    Path workingDirectory, String commandLineConfigFile, Map<String, String> environment) {
  ConfigurationInputs {
    workingDirectory =
        Objects.requireNonNull(workingDirectory, "workingDirectory").toAbsolutePath().normalize();
    environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
  }

  @Override
  public String toString() {
    return "ConfigurationInputs[workingDirectory="
        + workingDirectory
        + ", commandLineConfigFile="
        + commandLineConfigFile
        + ", environmentKeys="
        + new TreeSet<>(environment.keySet())
        + "]";
  }
}

enum ConfigurationMode {
  ENVIRONMENT,
  TOML
}

enum ConfigurationSource {
  ENV,
  TOML_MODERN,
  TOML_LEGACY,
  PRESET,
  DEFAULT
}

enum ConfigurationDiagnosticCode {
  CONFIG_FILE_NOT_FOUND,
  CONFIG_FILE_INVALID,
  CONFIG_TOML_INVALID,
  CONFIG_TYPE_INVALID,
  CONFIG_REQUIRED_MISSING,
  CONFIG_ENV_UNRESOLVED,
  CONFIG_URL_INVALID
}

record ConfigurationDiagnostic(ConfigurationDiagnosticCode code, String field) {
  ConfigurationDiagnostic {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(field, "field");
  }
}

enum SecretStatus {
  PRESENT,
  MISSING,
  UNRESOLVED
}

final class SensitiveConfigurationValue {
  private final String value;
  private final SecretStatus status;

  private SensitiveConfigurationValue(String value, SecretStatus status) {
    this.value = value;
    this.status = status;
  }

  static SensitiveConfigurationValue present(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("敏感配置值不能为空");
    }
    return new SensitiveConfigurationValue(value, SecretStatus.PRESENT);
  }

  static SensitiveConfigurationValue missing() {
    return new SensitiveConfigurationValue("", SecretStatus.MISSING);
  }

  static SensitiveConfigurationValue unresolved() {
    return new SensitiveConfigurationValue("", SecretStatus.UNRESOLVED);
  }

  SecretStatus status() {
    return status;
  }

  String reveal() {
    if (status != SecretStatus.PRESENT) {
      throw new IllegalStateException("敏感配置值不可用");
    }
    return value;
  }

  @Override
  public String toString() {
    return "[REDACTED]";
  }
}

record ActiveConfigurationSnapshot(
    String provider,
    String model,
    SensitiveConfigurationValue apiKey,
    URI baseUrl,
    Optional<String> systemPrompt,
    int historyMaxMessages,
    Map<String, ConfigurationSource> sources) {
  ActiveConfigurationSnapshot {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(apiKey, "apiKey");
    Objects.requireNonNull(baseUrl, "baseUrl");
    systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
    sources = Collections.unmodifiableMap(new TreeMap<>(sources));
  }

  ConfigurationSource source(String field) {
    return sources.get(field);
  }

  @Override
  public String toString() {
    return "ActiveConfigurationSnapshot[provider=PRESENT, model=PRESENT, apiKey=[REDACTED], "
        + "baseUrl=PRESENT, systemPrompt=[REDACTED], historyMaxMessages="
        + historyMaxMessages
        + "]";
  }
}

record ConfigurationReport(
    Map<String, ConfigurationSource> activeSources,
    Map<String, SecretStatus> sensitiveStatuses,
    Set<String> deferredPaths,
    Set<String> unknownPaths,
    List<ConfigurationDiagnostic> diagnostics) {
  ConfigurationReport {
    activeSources = Collections.unmodifiableMap(new TreeMap<>(activeSources));
    sensitiveStatuses = Collections.unmodifiableMap(new TreeMap<>(sensitiveStatuses));
    deferredPaths = Collections.unmodifiableSet(new TreeSet<>(deferredPaths));
    unknownPaths = Collections.unmodifiableSet(new TreeSet<>(unknownPaths));
    diagnostics = List.copyOf(diagnostics);
  }
}

record ConfigurationResolution(
    ConfigurationMode mode,
    Optional<Path> configFile,
    Optional<ActiveConfigurationSnapshot> active,
    ConfigurationReport report) {
  ConfigurationResolution {
    Objects.requireNonNull(mode, "mode");
    configFile = Objects.requireNonNull(configFile, "configFile");
    active = Objects.requireNonNull(active, "active");
    Objects.requireNonNull(report, "report");
  }

  ActiveConfigurationSnapshot requireActive() {
    return active.orElseThrow(() -> new ConfigurationResolutionException(report.diagnostics()));
  }
}
