package io.namei.agent.adapter.workspace;

import io.namei.agent.kernel.skill.SkillCatalogPort;
import io.namei.agent.kernel.skill.SkillCatalogSnapshot;
import io.namei.agent.kernel.skill.SkillContent;
import io.namei.agent.kernel.skill.SkillDescriptor;
import io.namei.agent.kernel.skill.SkillSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Strict, read-only reader for Java-owned Skill roots. It never creates files or follows links. */
public final class MarkdownSkillCatalogAdapter implements SkillCatalogPort {
  private static final String SKILL_FILE = "SKILL.md";
  private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern BINARY_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Path builtinRoot;
  private final Path workspaceRoot;
  private final SkillRequirementChecker requirements;
  private final SkillCatalogLimits limits;

  public MarkdownSkillCatalogAdapter(
      Path builtinRoot,
      Path workspaceRoot,
      SkillRequirementChecker requirements,
      SkillCatalogLimits limits) {
    this.builtinRoot = builtinRoot == null ? null : builtinRoot.toAbsolutePath().normalize();
    this.workspaceRoot = workspaceRoot == null ? null : workspaceRoot.toAbsolutePath().normalize();
    this.requirements = Objects.requireNonNull(requirements, "requirements");
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  @Override
  public SkillCatalogSnapshot snapshot() {
    List<LoadedSkill> merged = mergedSkills();
    return new SkillCatalogSnapshot(
        merged.stream().map(LoadedSkill::descriptor).toList(),
        merged.stream()
            .filter(skill -> skill.descriptor().available() && skill.descriptor().always())
            .map(skill -> new SkillContent(skill.name(), skill.body()))
            .toList());
  }

  @Override
  public Optional<SkillContent> readAvailable(String name) {
    if (!SkillDescriptor.isValidName(name)) {
      return Optional.empty();
    }
    return mergedSkills().stream()
        .filter(skill -> skill.name().equals(name) && skill.descriptor().available())
        .findFirst()
        .map(skill -> new SkillContent(skill.name(), skill.body()));
  }

  private List<LoadedSkill> mergedSkills() {
    Map<String, LoadedSkill> merged = new LinkedHashMap<>();
    readRoot(builtinRoot, SkillSource.BUILTIN).forEach(skill -> merged.put(skill.name(), skill));
    readRoot(workspaceRoot, SkillSource.WORKSPACE)
        .forEach(skill -> merged.put(skill.name(), skill));
    return List.copyOf(merged.values());
  }

  private List<LoadedSkill> readRoot(Path configuredRoot, SkillSource source) {
    Path root = realDirectory(configuredRoot).orElse(null);
    if (root == null) {
      return List.of();
    }
    try (var entries = Files.list(root)) {
      return entries
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .limit(limits.maxSkills())
          .map(candidate -> load(root, candidate, source))
          .flatMap(Optional::stream)
          .toList();
    } catch (IOException | RuntimeException ignored) {
      return List.of();
    }
  }

  private Optional<LoadedSkill> load(Path root, Path candidate, SkillSource source) {
    try {
      if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
        return Optional.empty();
      }
      Path directory = candidate.toRealPath();
      if (!directory.startsWith(root)) {
        return Optional.empty();
      }
      String directoryName = directory.getFileName().toString();
      Path markdown = directory.resolve(SKILL_FILE);
      if (!Files.isRegularFile(markdown, LinkOption.NOFOLLOW_LINKS)) {
        return Optional.empty();
      }
      Path realMarkdown = markdown.toRealPath();
      if (!realMarkdown.startsWith(directory) || Files.size(realMarkdown) > limits.maxFileBytes()) {
        return Optional.empty();
      }
      ParsedSkill parsed = parse(readUtf8(realMarkdown));
      if (!directoryName.equals(parsed.name())) {
        return Optional.empty();
      }
      boolean available = available(parsed.requirements());
      return Optional.of(
          new LoadedSkill(
              parsed.name(),
              new SkillDescriptor(
                  parsed.name(), parsed.description(), source, available, parsed.always()),
              parsed.body()));
    } catch (IOException | RuntimeException invalid) {
      return Optional.empty();
    }
  }

  private boolean available(Requirements required) {
    return required.binaries().stream().allMatch(requirements::binaryAvailable)
        && required.environment().stream().allMatch(requirements::environmentAvailable);
  }

  private static Optional<Path> realDirectory(Path candidate) {
    if (candidate == null || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    try {
      return Optional.of(candidate.toRealPath());
    } catch (IOException invalid) {
      return Optional.empty();
    }
  }

  private static String readUtf8(Path path) throws IOException {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(Files.readAllBytes(path)))
          .toString();
    } catch (CharacterCodingException invalid) {
      throw new IOException("Skill 不是有效 UTF-8", invalid);
    }
  }

  private static ParsedSkill parse(String raw) {
    String normalized = raw.replace("\r\n", "\n");
    if (!normalized.startsWith("---\n")) {
      throw new IllegalArgumentException("缺少 frontmatter");
    }
    int closing = normalized.indexOf("\n---\n", 4);
    if (closing < 0) {
      throw new IllegalArgumentException("frontmatter 未关闭");
    }
    Map<String, String> fields = frontmatter(normalized.substring(4, closing));
    String name = required(fields, "name");
    String description = required(fields, "description");
    Metadata metadata = metadata(fields.get("metadata"));
    String body = normalized.substring(closing + "\n---\n".length()).strip();
    if (body.isEmpty()) {
      throw new IllegalArgumentException("Skill 正文为空");
    }
    return new ParsedSkill(name, description, metadata.always(), metadata.requirements(), body);
  }

  private static Map<String, String> frontmatter(String value) {
    Map<String, String> fields = new LinkedHashMap<>();
    for (String line : value.split("\n", -1)) {
      if (line.isBlank()) {
        continue;
      }
      int delimiter = line.indexOf(':');
      if (delimiter < 1) {
        throw new IllegalArgumentException("frontmatter 行无效");
      }
      String key = line.substring(0, delimiter).strip();
      String content = unquote(line.substring(delimiter + 1).strip());
      if (key.isEmpty() || content.isEmpty() || fields.putIfAbsent(key, content) != null) {
        throw new IllegalArgumentException("frontmatter 字段无效");
      }
    }
    return fields;
  }

  private static String required(Map<String, String> fields, String key) {
    String value = fields.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("缺少 Skill " + key);
    }
    return value.strip();
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  private static Metadata metadata(String raw) {
    if (raw == null || raw.isBlank()) {
      return new Metadata(false, Requirements.empty());
    }
    try {
      JsonNode root = JSON.readTree(raw);
      if (root == null || !root.isObject()) {
        throw new IllegalArgumentException("metadata 必须是对象");
      }
      JsonNode configuration = root.has("akashic") ? root.path("akashic") : root.path("skill");
      if (!configuration.isObject()) {
        throw new IllegalArgumentException("metadata 缺少 Skill 配置");
      }
      boolean always = false;
      if (configuration.has("always")) {
        if (!configuration.path("always").isBoolean()) {
          throw new IllegalArgumentException("always 必须是布尔值");
        }
        always = configuration.path("always").asBoolean();
      }
      JsonNode required = configuration.path("requires");
      if (required.isMissingNode()) {
        return new Metadata(always, Requirements.empty());
      }
      if (!required.isObject()) {
        throw new IllegalArgumentException("requires 必须是对象");
      }
      return new Metadata(
          always,
          new Requirements(
              names(required.path("bins"), BINARY_NAME),
              names(required.path("env"), ENVIRONMENT_NAME)));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("metadata JSON 无效", invalid);
    }
  }

  private static List<String> names(JsonNode node, Pattern allowed) {
    if (node.isMissingNode()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw new IllegalArgumentException("requires 值必须是数组");
    }
    var values = new ArrayList<String>();
    for (JsonNode value : node) {
      String candidate = value.isTextual() ? value.asString() : "";
      if (!allowed.matcher(candidate).matches() || values.contains(candidate)) {
        throw new IllegalArgumentException("requires 名称无效");
      }
      values.add(candidate);
    }
    return List.copyOf(values);
  }

  private record ParsedSkill(
      String name, String description, boolean always, Requirements requirements, String body) {}

  private record Metadata(boolean always, Requirements requirements) {}

  private record Requirements(List<String> binaries, List<String> environment) {
    private static Requirements empty() {
      return new Requirements(List.of(), List.of());
    }
  }

  private record LoadedSkill(String name, SkillDescriptor descriptor, String body) {}
}
