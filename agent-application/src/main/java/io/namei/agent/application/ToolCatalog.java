package io.namei.agent.application;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolRisk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ToolCatalog {
  static final String SEARCH_TOOL_NAME = "tool_search";
  private static final int DEFAULT_TOP_K = 5;
  private static final int MAX_TOP_K = 10;
  private static final ToolDefinition SEARCH_DEFINITION =
      new ToolDefinition(
          SEARCH_TOOL_NAME,
          "搜索并解锁当前运行时允许使用的工具。",
          Map.of(
              "type",
              "object",
              "properties",
              Map.of(
                  "query",
                  Map.of("type", "string"),
                  "top_k",
                  Map.of("type", "integer", "enum", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, MAX_TOP_K))),
              "required",
              List.of("query"),
              "additionalProperties",
              false),
          ToolRisk.READ_ONLY,
          "tool-catalog-v1");

  private final Map<String, ToolCatalogEntry> entriesByName;
  private final boolean searchEnabled;

  public ToolCatalog(List<ToolCatalogEntry> entries) {
    Objects.requireNonNull(entries, "entries");
    var registered = new LinkedHashMap<String, ToolCatalogEntry>();
    for (ToolCatalogEntry entry : entries) {
      Objects.requireNonNull(entry, "entry");
      String name = entry.definition().name();
      if (SEARCH_TOOL_NAME.equals(name)) {
        throw new IllegalArgumentException("工具名称为保留名称: " + SEARCH_TOOL_NAME);
      }
      if (registered.putIfAbsent(name, entry) != null) {
        throw new IllegalArgumentException("工具名称重复: " + name);
      }
    }
    this.entriesByName = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(registered));
    this.searchEnabled =
        registered.values().stream()
            .anyMatch(entry -> entry.visibility() == ToolCatalogVisibility.DEFERRED);
  }

  public static ToolCatalog alwaysOn(List<Tool> tools) {
    Objects.requireNonNull(tools, "tools");
    return new ToolCatalog(
        tools.stream()
            .map(
                tool ->
                    new ToolCatalogEntry(
                        tool,
                        ToolCatalogVisibility.ALWAYS_ON,
                        ToolCatalogSource.BUILTIN,
                        "",
                        List.of()))
            .toList());
  }

  ToolCatalogSession newSession() {
    var initial = new ArrayList<String>();
    entriesByName.forEach(
        (name, entry) -> {
          if (entry.visibility() == ToolCatalogVisibility.ALWAYS_ON) {
            initial.add(name);
          }
        });
    if (searchEnabled) {
      initial.add(SEARCH_TOOL_NAME);
    }
    return new ToolCatalogSession(this, initial);
  }

  public List<ToolDefinition> initialDefinitions() {
    return definitions(newSession());
  }

  List<ToolDefinition> allDefinitions() {
    var definitions =
        entriesByName.values().stream()
            .map(ToolCatalogEntry::definition)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    if (searchEnabled) {
      definitions.add(SEARCH_DEFINITION);
    }
    return List.copyOf(definitions);
  }

  List<ToolDefinition> definitions(ToolCatalogSession session) {
    requireSession(session);
    var definitions = new ArrayList<ToolDefinition>();
    for (String name : session.visibleNamesInOrder()) {
      if (SEARCH_TOOL_NAME.equals(name)) {
        definitions.add(SEARCH_DEFINITION);
      } else {
        ToolCatalogEntry entry = entriesByName.get(name);
        if (entry == null) {
          throw new IllegalStateException("Tool Catalog Session 包含未知工具");
        }
        definitions.add(entry.definition());
      }
    }
    return List.copyOf(definitions);
  }

  Tool tool(String name) {
    ToolCatalogEntry entry = entriesByName.get(name);
    return entry == null ? null : entry.tool();
  }

  ToolDefinition definition(String name) {
    if (SEARCH_TOOL_NAME.equals(name) && searchEnabled) {
      return SEARCH_DEFINITION;
    }
    ToolCatalogEntry entry = entriesByName.get(name);
    return entry == null ? null : entry.definition();
  }

  boolean isVisible(ToolCatalogSession session, String name) {
    requireSession(session);
    return session.isVisible(name);
  }

  boolean isDeferred(String name) {
    ToolCatalogEntry entry = entriesByName.get(name);
    return entry != null && entry.visibility() == ToolCatalogVisibility.DEFERRED;
  }

  boolean isSearchTool(String name) {
    return searchEnabled && SEARCH_TOOL_NAME.equals(name);
  }

  ToolCatalogSearchResult search(ToolCatalogSession session, String rawQuery, int topK) {
    requireSession(session);
    String query = Objects.requireNonNullElse(rawQuery, "").strip();
    if (query.isBlank()) {
      throw new IllegalArgumentException("工具搜索 query 不能为空");
    }
    if (topK < 1 || topK > MAX_TOP_K) {
      throw new IllegalArgumentException("工具搜索 top_k 必须在 1 到 10 之间");
    }
    if (query.regionMatches(true, 0, "select:", 0, "select:".length())) {
      return select(session, query.substring("select:".length()));
    }
    List<String> queryTokens = tokens(query);
    var candidates = new ArrayList<ScoredEntry>();
    for (Map.Entry<String, ToolCatalogEntry> item : entriesByName.entrySet()) {
      String name = item.getKey();
      ToolCatalogEntry entry = item.getValue();
      if (entry.visibility() != ToolCatalogVisibility.DEFERRED || session.isVisible(name)) {
        continue;
      }
      int score = score(entry.definition(), entry, query, queryTokens);
      if (score > 0) {
        candidates.add(new ScoredEntry(name, entry, score));
      }
    }
    candidates.sort(
        Comparator.comparingInt(ScoredEntry::score).reversed().thenComparing(ScoredEntry::name));
    var matched = new ArrayList<ToolCatalogMatch>();
    var unlocked = new ArrayList<String>();
    for (ScoredEntry candidate : candidates.stream().limit(topK).toList()) {
      matched.add(match(candidate.name(), candidate.entry()));
      if (session.unlock(this, candidate.name())) {
        unlocked.add(candidate.name());
      }
    }
    return new ToolCatalogSearchResult(matched, unlocked, List.of());
  }

  ToolCatalogSearchResult search(ToolCatalogSession session, String query) {
    return search(session, query, DEFAULT_TOP_K);
  }

  private ToolCatalogSearchResult select(ToolCatalogSession session, String rawNames) {
    var matched = new ArrayList<ToolCatalogMatch>();
    var unlocked = new ArrayList<String>();
    var alreadyLoaded = new ArrayList<String>();
    var requested = new LinkedHashSet<String>();
    for (String rawName : rawNames.split(",", -1)) {
      String name = rawName.strip();
      if (!name.isBlank()) {
        requested.add(name);
      }
    }
    for (String name : requested) {
      if (session.isVisible(name)) {
        alreadyLoaded.add(name);
        continue;
      }
      ToolCatalogEntry entry = entriesByName.get(name);
      if (entry == null || entry.visibility() != ToolCatalogVisibility.DEFERRED) {
        continue;
      }
      matched.add(match(name, entry));
      if (session.unlock(this, name)) {
        unlocked.add(name);
      }
    }
    return new ToolCatalogSearchResult(matched, unlocked, alreadyLoaded);
  }

  private static int score(
      ToolDefinition definition, ToolCatalogEntry entry, String query, List<String> queryTokens) {
    String indexed = index(definition, entry);
    if (queryTokens.isEmpty() || queryTokens.stream().anyMatch(token -> !indexed.contains(token))) {
      return 0;
    }
    String normalizedQuery = normalize(query);
    int score = queryTokens.size() * 10;
    if (indexed.contains(normalizedQuery)) {
      score += 20;
    }
    String normalizedName = normalize(definition.name());
    if (normalizedName.contains(normalizedQuery)) {
      score += 10;
    }
    return score;
  }

  private static String index(ToolDefinition definition, ToolCatalogEntry entry) {
    var values = new ArrayList<String>();
    values.add(definition.name());
    values.add(definition.description());
    values.addAll(entry.searchHints());
    return String.join(" ", values.stream().map(ToolCatalog::normalize).toList());
  }

  private static List<String> tokens(String value) {
    String normalized = normalize(value);
    var result = new LinkedHashSet<String>();
    var word = new StringBuilder();
    var cjk = new StringBuilder();
    for (int offset = 0; offset < normalized.length(); ) {
      int codePoint = normalized.codePointAt(offset);
      offset += Character.charCount(codePoint);
      if (isCjk(codePoint)) {
        flushWord(word, result);
        cjk.appendCodePoint(codePoint);
      } else if (Character.isLetterOrDigit(codePoint)) {
        flushCjk(cjk, result);
        word.appendCodePoint(codePoint);
      } else {
        flushWord(word, result);
        flushCjk(cjk, result);
      }
    }
    flushWord(word, result);
    flushCjk(cjk, result);
    return List.copyOf(result);
  }

  private static void flushWord(StringBuilder word, Set<String> destination) {
    if (!word.isEmpty()) {
      destination.add(word.toString());
      word.setLength(0);
    }
  }

  private static void flushCjk(StringBuilder cjk, Set<String> destination) {
    if (cjk.isEmpty()) {
      return;
    }
    String text = cjk.toString();
    destination.add(text);
    int[] points = text.codePoints().toArray();
    for (int index = 0; index < points.length; index++) {
      destination.add(new String(points, index, 1));
      if (index + 1 < points.length) {
        destination.add(new String(points, index, 2));
      }
    }
    cjk.setLength(0);
  }

  private static boolean isCjk(int codePoint) {
    Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
        || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
  }

  private static String normalize(String value) {
    return Objects.requireNonNull(value, "value").strip().toLowerCase(Locale.ROOT);
  }

  private static ToolCatalogMatch match(String name, ToolCatalogEntry entry) {
    ToolDefinition definition = entry.definition();
    return new ToolCatalogMatch(
        name,
        summary(definition.description()),
        definition.risk(),
        entry.source(),
        entry.visibility() == ToolCatalogVisibility.ALWAYS_ON);
  }

  private static String summary(String description) {
    int count = description.codePointCount(0, description.length());
    if (count <= 120) {
      return description;
    }
    int end = description.offsetByCodePoints(0, 120);
    return description.substring(0, end);
  }

  private void requireSession(ToolCatalogSession session) {
    Objects.requireNonNull(session, "session");
    if (!session.isOwnedBy(this)) {
      throw new IllegalArgumentException("Tool Catalog Session 不属于当前 Catalog");
    }
  }

  private record ScoredEntry(String name, ToolCatalogEntry entry, int score) {}
}
