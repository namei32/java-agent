package io.namei.agent.adapter.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.namei.agent.kernel.tool.ToolRisk;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class McpConfigLoader {
  static final int MAX_CONFIG_BYTES = 1_048_576;
  private static final int MAX_ARGUMENTS = 128;
  private static final int MAX_ARGUMENT_BYTES = 4_096;
  private static final int MAX_ARGUMENT_TOTAL_BYTES = 65_536;
  private static final int MAX_REMOTE_NAME_BYTES = 512;
  private static final Pattern SERVER_ID = Pattern.compile("[a-z][a-z0-9_-]{0,15}");
  private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
  private static final Set<String> SHELL_NAMES =
      Set.of("sh", "bash", "zsh", "dash", "fish", "cmd.exe", "powershell", "pwsh");
  private static final ObjectMapper JSON = createJsonMapper();

  private final McpConfigFileReader fileReader;

  public McpConfigLoader() {
    this(new DefaultMcpConfigFileReader());
  }

  McpConfigLoader(McpConfigFileReader fileReader) {
    this.fileReader = Objects.requireNonNull(fileReader, "fileReader");
  }

  public McpConfiguration load(McpSettings settings) {
    Objects.requireNonNull(settings, "settings");
    if (settings.mode() == McpMode.DISABLED) {
      return McpConfiguration.disabled();
    }
    try {
      byte[] bytes = fileReader.read(settings.configFile(), MAX_CONFIG_BYTES);
      RawConfiguration raw = JSON.readValue(bytes, RawConfiguration.class);
      return validate(raw, settings);
    } catch (McpConfigurationException exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw new McpConfigurationException();
    }
  }

  private static McpConfiguration validate(RawConfiguration raw, McpSettings settings)
      throws IOException {
    if (raw == null || raw.schemaVersion() == null || raw.schemaVersion() != 1) {
      throw new McpConfigurationException();
    }
    List<RawServer> rawServers = requireList(raw.servers());
    if (rawServers.isEmpty() || rawServers.size() > settings.maxServers()) {
      throw new McpConfigurationException();
    }
    Set<String> serverIds = new HashSet<>();
    List<McpServerDefinition> servers = new ArrayList<>(rawServers.size());
    for (RawServer rawServer : rawServers) {
      servers.add(validateServer(rawServer, settings, serverIds));
    }
    return new McpConfiguration(1, servers);
  }

  private static McpServerDefinition validateServer(
      RawServer raw, McpSettings settings, Set<String> serverIds) throws IOException {
    if (raw == null
        || raw.id() == null
        || !SERVER_ID.matcher(raw.id()).matches()
        || !serverIds.add(raw.id())
        || !"STDIO".equals(raw.transport())) {
      throw new McpConfigurationException();
    }
    Path executable = realExecutable(raw.executable());
    Path workingDirectory = realDirectory(raw.workingDirectory());
    List<String> arguments = validateArguments(raw.arguments());
    List<String> environmentVariables = validateEnvironment(raw.environmentVariables());
    Map<String, McpToolPolicy> tools = validateTools(raw.tools(), settings.maxToolsPerServer());
    return new McpServerDefinition(
        raw.id(), executable, arguments, workingDirectory, environmentVariables, tools);
  }

  private static Path realExecutable(String value) throws IOException {
    Path candidate = absolutePath(value);
    Path real = candidate.toRealPath();
    String fileName = real.getFileName().toString().toLowerCase(Locale.ROOT);
    if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)
        || !Files.isExecutable(real)
        || SHELL_NAMES.contains(fileName)) {
      throw new McpConfigurationException();
    }
    return real;
  }

  private static Path realDirectory(String value) throws IOException {
    Path candidate = absolutePath(value);
    Path real = candidate.toRealPath();
    if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
      throw new McpConfigurationException();
    }
    return real;
  }

  private static Path absolutePath(String value) {
    if (value == null || value.isBlank() || containsControl(value)) {
      throw new McpConfigurationException();
    }
    Path path = Path.of(value);
    if (!path.isAbsolute()) {
      throw new McpConfigurationException();
    }
    return path.normalize();
  }

  private static List<String> validateArguments(List<String> rawArguments) {
    List<String> arguments = requireList(rawArguments);
    if (arguments.size() > MAX_ARGUMENTS) {
      throw new McpConfigurationException();
    }
    int total = 0;
    for (String argument : arguments) {
      if (argument == null || argument.isEmpty() || containsControl(argument)) {
        throw new McpConfigurationException();
      }
      int bytes = argument.getBytes(StandardCharsets.UTF_8).length;
      if (bytes > MAX_ARGUMENT_BYTES || total > MAX_ARGUMENT_TOTAL_BYTES - bytes) {
        throw new McpConfigurationException();
      }
      total += bytes;
    }
    return List.copyOf(arguments);
  }

  private static List<String> validateEnvironment(List<String> rawNames) {
    List<String> names = requireList(rawNames);
    Set<String> unique = new HashSet<>();
    for (String name : names) {
      if (name == null || !ENVIRONMENT_NAME.matcher(name).matches() || !unique.add(name)) {
        throw new McpConfigurationException();
      }
    }
    return List.copyOf(names);
  }

  private static Map<String, McpToolPolicy> validateTools(
      Map<String, RawToolPolicy> rawTools, int maxTools) {
    if (rawTools == null || rawTools.size() > maxTools) {
      throw new McpConfigurationException();
    }
    Map<String, McpToolPolicy> tools = new LinkedHashMap<>();
    for (Map.Entry<String, RawToolPolicy> entry : rawTools.entrySet()) {
      String remoteName = entry.getKey();
      RawToolPolicy rawPolicy = entry.getValue();
      if (remoteName == null
          || remoteName.isBlank()
          || containsControl(remoteName)
          || remoteName.getBytes(StandardCharsets.UTF_8).length > MAX_REMOTE_NAME_BYTES
          || rawPolicy == null
          || rawPolicy.enabled() == null
          || !"READ_ONLY".equals(rawPolicy.risk())) {
        throw new McpConfigurationException();
      }
      tools.put(remoteName, new McpToolPolicy(rawPolicy.enabled(), ToolRisk.READ_ONLY));
    }
    return Map.copyOf(tools);
  }

  private static boolean containsControl(String value) {
    return value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint));
  }

  private static <T> List<T> requireList(List<T> value) {
    if (value == null) {
      throw new McpConfigurationException();
    }
    return value;
  }

  private static ObjectMapper createJsonMapper() {
    StreamReadConstraints constraints =
        StreamReadConstraints.builder()
            .maxDocumentLength(MAX_CONFIG_BYTES)
            .maxNestingDepth(32)
            .maxTokenCount(100_000)
            .maxStringLength(65_536)
            .maxNameLength(512)
            .build();
    JsonFactory factory =
        JsonFactory.builder()
            .streamReadConstraints(constraints)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    return JsonMapper.builder(factory)
        .enable(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
            DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .build();
  }

  private record RawConfiguration(
      @JsonProperty("schemaVersion") Integer schemaVersion,
      @JsonProperty("servers") List<RawServer> servers) {}

  private record RawServer(
      @JsonProperty("id") String id,
      @JsonProperty("transport") String transport,
      @JsonProperty("executable") String executable,
      @JsonProperty("arguments") List<String> arguments,
      @JsonProperty("workingDirectory") String workingDirectory,
      @JsonProperty("environmentVariables") List<String> environmentVariables,
      @JsonProperty("tools") Map<String, RawToolPolicy> tools) {}

  private record RawToolPolicy(
      @JsonProperty("enabled") Boolean enabled, @JsonProperty("risk") String risk) {}
}
