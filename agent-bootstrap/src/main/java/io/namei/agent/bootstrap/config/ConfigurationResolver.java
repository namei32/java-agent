package io.namei.agent.bootstrap.config;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.tomlj.TomlTable;

final class ConfigurationResolver {
  private static final String DOCUMENT_FIELD = "$document";
  private static final Pattern COMPLETE_ENVIRONMENT_PLACEHOLDER =
      Pattern.compile("^\\$\\{([A-Za-z0-9_]+)}$");
  private static final Set<String> ACTIVE_TOML_PATHS =
      Set.of(
          "llm.provider",
          "provider",
          "llm.main.model",
          "model",
          "llm.main.api_key",
          "api_key",
          "llm.main.base_url",
          "base_url",
          "agent.system_prompt",
          "system_prompt",
          "agent.context.memory_window",
          "memory_window");
  private static final Set<String> DEFERRED_PREFIXES =
      Set.of(
          "llm.fast",
          "llm.agent",
          "llm.vl",
          "llm.main.multimodal",
          "llm.main.thinking",
          "llm.main.enable_thinking",
          "llm.main.reasoning_effort",
          "extra_body",
          "agent.max_tokens",
          "agent.max_iterations",
          "agent.tools",
          "agent.maintenance",
          "agent.wiring",
          "channels",
          "plugins",
          "memory",
          "proactive",
          "integrations",
          "peer_agents");
  private static final Map<String, String> PROVIDER_PRESETS =
      Map.of(
          "deepseek", "https://api.deepseek.com/v1",
          "openai", "https://api.openai.com/v1",
          "qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1");

  ConfigurationResolution resolve(ConfigurationInputs inputs) {
    var selection = selectConfiguration(inputs);
    if (!selection.diagnostics().isEmpty()) {
      return failed(
          ConfigurationMode.TOML,
          selection.path(),
          Map.of(),
          Map.of(),
          Set.of(),
          Set.of(),
          selection.diagnostics());
    }
    if (selection.path().isEmpty()) {
      return resolveEnvironment(inputs.environment());
    }
    return resolveToml(selection.path().orElseThrow(), inputs.environment());
  }

  private ConfigurationSelection selectConfiguration(ConfigurationInputs inputs) {
    String commandLine = provided(inputs.commandLineConfigFile());
    String environment = provided(inputs.environment().get("NAMEI_CONFIG_FILE"));
    Path selected = null;
    boolean explicit = false;
    if (commandLine != null) {
      selected = resolvePath(inputs.workingDirectory(), commandLine);
      explicit = true;
    } else if (environment != null) {
      selected = resolvePath(inputs.workingDirectory(), environment);
      explicit = true;
    } else {
      Path defaultPath = inputs.workingDirectory().resolve("config.toml").normalize();
      if (Files.exists(defaultPath)) {
        selected = defaultPath;
      }
    }

    if (selected == null) {
      return new ConfigurationSelection(Optional.empty(), List.of());
    }
    if (!selected.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".toml")) {
      return invalidSelection(selected, ConfigurationDiagnosticCode.CONFIG_FILE_INVALID);
    }
    if (!Files.exists(selected)) {
      return invalidSelection(
          selected,
          explicit
              ? ConfigurationDiagnosticCode.CONFIG_FILE_NOT_FOUND
              : ConfigurationDiagnosticCode.CONFIG_FILE_INVALID);
    }
    if (!Files.isRegularFile(selected)) {
      return invalidSelection(selected, ConfigurationDiagnosticCode.CONFIG_FILE_INVALID);
    }
    return new ConfigurationSelection(Optional.of(selected), List.of());
  }

  private ConfigurationResolution resolveEnvironment(Map<String, String> environment) {
    var diagnostics = new ArrayList<ConfigurationDiagnostic>();
    var sources = new LinkedHashMap<String, ConfigurationSource>();
    var sensitiveStatuses = new HashMap<String, SecretStatus>();

    String model = requiredEnvironment(environment, "OPENAI_MODEL", "model", sources, diagnostics);
    String rawApiKey =
        requiredEnvironment(environment, "OPENAI_API_KEY", "apiKey", sources, diagnostics);
    String rawBaseUrl =
        requiredEnvironment(environment, "OPENAI_BASE_URL", "baseUrl", sources, diagnostics);
    URI baseUrl = validateBaseUrl(rawBaseUrl, "OPENAI_BASE_URL", diagnostics);
    SensitiveConfigurationValue apiKey =
        rawApiKey == null
            ? SensitiveConfigurationValue.missing()
            : SensitiveConfigurationValue.present(rawApiKey);
    sensitiveStatuses.put("apiKey", apiKey.status());
    sources.put("provider", ConfigurationSource.DEFAULT);
    sources.put("systemPrompt", ConfigurationSource.DEFAULT);
    sources.put("historyMaxMessages", ConfigurationSource.DEFAULT);

    Optional<ActiveConfigurationSnapshot> active = Optional.empty();
    if (diagnostics.isEmpty()) {
      active =
          Optional.of(
              new ActiveConfigurationSnapshot(
                  "openai-compatible",
                  model,
                  apiKey,
                  baseUrl,
                  Optional.empty(),
                  40,
                  sources));
    }
    return new ConfigurationResolution(
        ConfigurationMode.ENVIRONMENT,
        Optional.empty(),
        active,
        report(sources, sensitiveStatuses, Set.of(), Set.of(), diagnostics));
  }

  private ConfigurationResolution resolveToml(Path path, Map<String, String> environment) {
    ConfigurationDocument.LoadResult loaded = ConfigurationDocument.load(path);
    if (loaded.document().isEmpty()) {
      return failed(
          ConfigurationMode.TOML,
          Optional.of(path),
          Map.of(),
          Map.of(),
          Set.of(),
          Set.of(),
          List.of(loaded.diagnostic().orElseThrow()));
    }

    TomlTable root = loaded.document().orElseThrow().root();
    var diagnostics = new ArrayList<ConfigurationDiagnostic>();
    var sources = new LinkedHashMap<String, ConfigurationSource>();
    var sensitiveStatuses = new HashMap<String, SecretStatus>();

    ResolvedValue<String> provider =
        resolveString(
            root,
            environment,
            null,
            "llm.provider",
            "provider",
            null,
            null,
            "llm.provider",
            "provider",
            sources,
            diagnostics);
    ResolvedValue<String> model =
        resolveString(
            root,
            environment,
            "OPENAI_MODEL",
            "llm.main.model",
            "model",
            null,
            null,
            "llm.main.model",
            "model",
            sources,
            diagnostics);
    ResolvedValue<String> rawApiKey =
        resolveString(
            root,
            environment,
            "OPENAI_API_KEY",
            "llm.main.api_key",
            "api_key",
            null,
            null,
            "llm.main.api_key",
            "apiKey",
            sources,
            diagnostics);
    validateRequired(provider, "llm.provider", diagnostics);
    validateRequired(model, "llm.main.model", diagnostics);
    String preset = provider.value() == null ? null : PROVIDER_PRESETS.get(provider.value());
    ResolvedValue<String> rawBaseUrl =
        resolveString(
            root,
            environment,
            "OPENAI_BASE_URL",
            "llm.main.base_url",
            "base_url",
            preset,
            preset == null ? null : ConfigurationSource.PRESET,
            "llm.main.base_url",
            "baseUrl",
            sources,
            diagnostics);
    ResolvedValue<String> systemPrompt =
        resolveString(
            root,
            environment,
            null,
            "agent.system_prompt",
            "system_prompt",
            "You are a helpful assistant.",
            ConfigurationSource.DEFAULT,
            "agent.system_prompt",
            "systemPrompt",
            sources,
            diagnostics);
    ResolvedValue<Integer> historyMaxMessages =
        resolveInteger(
            root,
            "agent.context.memory_window",
            "memory_window",
            40,
            "historyMaxMessages",
            sources,
            diagnostics);

    SensitiveConfigurationValue apiKey =
        resolveApiKey(rawApiKey, environment, diagnostics, sensitiveStatuses);
    URI baseUrl = validateBaseUrl(rawBaseUrl.value(), rawBaseUrl.field(), diagnostics);
    var classified = classifyPaths(root);

    Optional<ActiveConfigurationSnapshot> active = Optional.empty();
    if (diagnostics.isEmpty()) {
      active =
          Optional.of(
              new ActiveConfigurationSnapshot(
                  provider.value(),
                  model.value(),
                  apiKey,
                  baseUrl,
                  Optional.of(systemPrompt.value()),
                  historyMaxMessages.value(),
                  sources));
    }
    return new ConfigurationResolution(
        ConfigurationMode.TOML,
        Optional.of(path),
        active,
        report(
            sources,
            sensitiveStatuses,
            classified.deferredPaths(),
            classified.unknownPaths(),
            diagnostics));
  }

  private static ResolvedValue<String> resolveString(
      TomlTable root,
      Map<String, String> environment,
      String environmentKey,
      String modernPath,
      String legacyPath,
      String fallback,
      ConfigurationSource fallbackSource,
      String requiredField,
      String reportField,
      Map<String, ConfigurationSource> sources,
      List<ConfigurationDiagnostic> diagnostics) {
    String environmentValue = environmentKey == null ? null : provided(environment.get(environmentKey));
    if (environmentValue != null) {
      sources.put(reportField, ConfigurationSource.ENV);
      return new ResolvedValue<>(environmentValue, ConfigurationSource.ENV, environmentKey);
    }

    Candidate modern = stringCandidate(root, modernPath, ConfigurationSource.TOML_MODERN, diagnostics);
    if (!modern.valid()) {
      return new ResolvedValue<>(null, null, modernPath);
    }
    if (modern.value() != null && !modern.value().isEmpty()) {
      sources.put(reportField, modern.source());
      return new ResolvedValue<>(modern.value(), modern.source(), modernPath);
    }

    Candidate legacy = stringCandidate(root, legacyPath, ConfigurationSource.TOML_LEGACY, diagnostics);
    if (!legacy.valid()) {
      return new ResolvedValue<>(null, null, legacyPath);
    }
    if (legacy.value() != null && !legacy.value().isEmpty()) {
      sources.put(reportField, legacy.source());
      return new ResolvedValue<>(legacy.value(), legacy.source(), legacyPath);
    }

    if (fallback != null) {
      sources.put(reportField, fallbackSource);
      return new ResolvedValue<>(fallback, fallbackSource, requiredField);
    }
    diagnostics.add(
        new ConfigurationDiagnostic(
            ConfigurationDiagnosticCode.CONFIG_REQUIRED_MISSING, requiredField));
    return new ResolvedValue<>(null, null, requiredField);
  }

  private static Candidate stringCandidate(
      TomlTable root,
      String path,
      ConfigurationSource source,
      List<ConfigurationDiagnostic> diagnostics) {
    if (!root.contains(path)) {
      return new Candidate(true, null, source);
    }
    if (!root.isString(path)) {
      diagnostics.add(
          new ConfigurationDiagnostic(ConfigurationDiagnosticCode.CONFIG_TYPE_INVALID, path));
      return new Candidate(false, null, source);
    }
    return new Candidate(true, root.getString(path), source);
  }

  private static ResolvedValue<Integer> resolveInteger(
      TomlTable root,
      String modernPath,
      String legacyPath,
      int fallback,
      String reportField,
      Map<String, ConfigurationSource> sources,
      List<ConfigurationDiagnostic> diagnostics) {
    if (root.contains(modernPath)) {
      return integerCandidate(
          root,
          modernPath,
          ConfigurationSource.TOML_MODERN,
          reportField,
          sources,
          diagnostics);
    }
    if (root.contains(legacyPath)) {
      return integerCandidate(
          root,
          legacyPath,
          ConfigurationSource.TOML_LEGACY,
          reportField,
          sources,
          diagnostics);
    }
    sources.put(reportField, ConfigurationSource.DEFAULT);
    return new ResolvedValue<>(fallback, ConfigurationSource.DEFAULT, modernPath);
  }

  private static ResolvedValue<Integer> integerCandidate(
      TomlTable root,
      String path,
      ConfigurationSource source,
      String reportField,
      Map<String, ConfigurationSource> sources,
      List<ConfigurationDiagnostic> diagnostics) {
    if (!root.isLong(path)) {
      diagnostics.add(
          new ConfigurationDiagnostic(ConfigurationDiagnosticCode.CONFIG_TYPE_INVALID, path));
      return new ResolvedValue<>(null, null, path);
    }
    long value = root.getLong(path);
    if (value < 0 || value > Integer.MAX_VALUE) {
      diagnostics.add(
          new ConfigurationDiagnostic(ConfigurationDiagnosticCode.CONFIG_TYPE_INVALID, path));
      return new ResolvedValue<>(null, null, path);
    }
    sources.put(reportField, source);
    return new ResolvedValue<>((int) value, source, path);
  }

  private static SensitiveConfigurationValue resolveApiKey(
      ResolvedValue<String> raw,
      Map<String, String> environment,
      List<ConfigurationDiagnostic> diagnostics,
      Map<String, SecretStatus> sensitiveStatuses) {
    if (raw.value() == null) {
      sensitiveStatuses.put("apiKey", SecretStatus.MISSING);
      return SensitiveConfigurationValue.missing();
    }
    if (raw.value().isBlank()) {
      diagnostics.add(
          new ConfigurationDiagnostic(
              ConfigurationDiagnosticCode.CONFIG_REQUIRED_MISSING, raw.field()));
      sensitiveStatuses.put("apiKey", SecretStatus.MISSING);
      return SensitiveConfigurationValue.missing();
    }
    if (raw.source() == ConfigurationSource.ENV) {
      sensitiveStatuses.put("apiKey", SecretStatus.PRESENT);
      return SensitiveConfigurationValue.present(raw.value());
    }

    var matcher = COMPLETE_ENVIRONMENT_PLACEHOLDER.matcher(raw.value());
    if (matcher.matches()) {
      String resolved = provided(environment.get(matcher.group(1)));
      if (resolved == null) {
        diagnostics.add(
            new ConfigurationDiagnostic(
                ConfigurationDiagnosticCode.CONFIG_ENV_UNRESOLVED, raw.field()));
        sensitiveStatuses.put("apiKey", SecretStatus.UNRESOLVED);
        return SensitiveConfigurationValue.unresolved();
      }
      sensitiveStatuses.put("apiKey", SecretStatus.PRESENT);
      return SensitiveConfigurationValue.present(resolved);
    }
    if (raw.value().contains("${")) {
      diagnostics.add(
          new ConfigurationDiagnostic(
              ConfigurationDiagnosticCode.CONFIG_ENV_UNRESOLVED, raw.field()));
      sensitiveStatuses.put("apiKey", SecretStatus.UNRESOLVED);
      return SensitiveConfigurationValue.unresolved();
    }
    sensitiveStatuses.put("apiKey", SecretStatus.PRESENT);
    return SensitiveConfigurationValue.present(raw.value());
  }

  private static URI validateBaseUrl(
      String raw, String field, List<ConfigurationDiagnostic> diagnostics) {
    if (raw == null) {
      return null;
    }
    try {
      URI uri = URI.create(raw);
      if (uri.getHost() == null
          || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
        throw new IllegalArgumentException("unsupported URI");
      }
      return uri;
    } catch (IllegalArgumentException exception) {
      diagnostics.add(
          new ConfigurationDiagnostic(ConfigurationDiagnosticCode.CONFIG_URL_INVALID, field));
      return null;
    }
  }

  private static PathClassification classifyPaths(TomlTable root) {
    var leaves = new HashSet<String>();
    collectLeaves(root, "", leaves);
    var deferred = new HashSet<String>();
    var unknown = new HashSet<String>();
    for (String path : leaves) {
      if (ACTIVE_TOML_PATHS.contains(path)) {
        continue;
      }
      if (isDeferred(path)) {
        deferred.add(path);
      } else {
        unknown.add(path);
      }
    }
    return new PathClassification(deferred, unknown);
  }

  private static void collectLeaves(TomlTable table, String prefix, Set<String> leaves) {
    for (String key : table.keySet()) {
      String path = prefix.isEmpty() ? key : prefix + "." + key;
      Object value = table.get(List.of(key));
      if (value instanceof TomlTable nested) {
        collectLeaves(nested, path, leaves);
      } else {
        leaves.add(path);
      }
    }
  }

  private static boolean isDeferred(String path) {
    return DEFERRED_PREFIXES.stream()
        .anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "."));
  }

  private static String requiredEnvironment(
      Map<String, String> environment,
      String environmentKey,
      String reportField,
      Map<String, ConfigurationSource> sources,
      List<ConfigurationDiagnostic> diagnostics) {
    String value = provided(environment.get(environmentKey));
    if (value == null) {
      diagnostics.add(
          new ConfigurationDiagnostic(
              ConfigurationDiagnosticCode.CONFIG_REQUIRED_MISSING, environmentKey));
      return null;
    }
    sources.put(reportField, ConfigurationSource.ENV);
    return value;
  }

  private static ConfigurationResolution failed(
      ConfigurationMode mode,
      Optional<Path> path,
      Map<String, ConfigurationSource> sources,
      Map<String, SecretStatus> sensitiveStatuses,
      Set<String> deferred,
      Set<String> unknown,
      List<ConfigurationDiagnostic> diagnostics) {
    return new ConfigurationResolution(
        mode,
        path,
        Optional.empty(),
        report(sources, sensitiveStatuses, deferred, unknown, diagnostics));
  }

  private static ConfigurationReport report(
      Map<String, ConfigurationSource> sources,
      Map<String, SecretStatus> sensitiveStatuses,
      Set<String> deferred,
      Set<String> unknown,
      List<ConfigurationDiagnostic> diagnostics) {
    return new ConfigurationReport(
        sources, sensitiveStatuses, deferred, unknown, diagnostics);
  }

  private static ConfigurationSelection invalidSelection(
      Path path, ConfigurationDiagnosticCode code) {
    return new ConfigurationSelection(
        Optional.of(path), List.of(new ConfigurationDiagnostic(code, DOCUMENT_FIELD)));
  }

  private static Path resolvePath(Path workingDirectory, String configured) {
    Path path = Path.of(configured);
    if (!path.isAbsolute()) {
      path = workingDirectory.resolve(path);
    }
    return path.toAbsolutePath().normalize();
  }

  private static String provided(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value;
  }

  private static void validateRequired(
      ResolvedValue<String> value,
      String fallbackField,
      List<ConfigurationDiagnostic> diagnostics) {
    if (value.value() != null && value.value().isBlank()) {
      diagnostics.add(
          new ConfigurationDiagnostic(
              ConfigurationDiagnosticCode.CONFIG_REQUIRED_MISSING,
              value.field() == null ? fallbackField : value.field()));
    }
  }

  private record ConfigurationSelection(
      Optional<Path> path, List<ConfigurationDiagnostic> diagnostics) {}

  private record Candidate(boolean valid, String value, ConfigurationSource source) {}

  private record ResolvedValue<T>(T value, ConfigurationSource source, String field) {}

  private record PathClassification(Set<String> deferredPaths, Set<String> unknownPaths) {}
}
