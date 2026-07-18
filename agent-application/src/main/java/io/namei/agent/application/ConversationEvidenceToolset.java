package io.namei.agent.application;

import io.namei.agent.kernel.evidence.ConversationEvidenceMessage;
import io.namei.agent.kernel.evidence.ConversationEvidencePage;
import io.namei.agent.kernel.evidence.ConversationEvidenceReference;
import io.namei.agent.kernel.evidence.ConversationEvidenceRole;
import io.namei.agent.kernel.evidence.ConversationEvidenceSearchQuery;
import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Built-in, deferred read-only tools over the opaque current-turn evidence scope. */
public final class ConversationEvidenceToolset {
  static final String VERSION = "conversation-evidence-v1";
  static final int MAX_FETCH_IDS = 16;
  static final int MAX_CONTEXT = 10;
  static final int MAX_QUERY_CODE_POINTS = 256;
  static final int MAX_SEARCH_LIMIT = 50;
  static final int MAX_SEARCH_OFFSET = 1_000;
  public static final int MAX_PROJECTED_CODE_POINTS = 12_000;
  static final int MAX_PREVIEW_LINES = 50;
  private static final String INVALID_ARGUMENT = "CONVERSATION_EVIDENCE_INVALID_ARGUMENT";
  private static final String UNAVAILABLE = "CONVERSATION_EVIDENCE_UNAVAILABLE";
  private static final String CITATION_FORMAT = "§cited:[id1,id2,...]§";
  private static final String CITATION_RULE = "使用会话证据时，请在最终文本末尾按指定格式标注引用。";
  private static final Pattern UNICODE_WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
  private static final ConversationEvidenceToolset DISABLED =
      new ConversationEvidenceToolset(List.of());

  private final List<Tool> tools;

  private ConversationEvidenceToolset(List<Tool> tools) {
    this.tools = List.copyOf(tools);
  }

  public static ConversationEvidenceToolset disabled() {
    return DISABLED;
  }

  public static ConversationEvidenceToolset enabled() {
    return new ConversationEvidenceToolset(
        List.of(new FetchMessagesTool(), new SearchMessagesTool()));
  }

  public List<Tool> tools() {
    return tools;
  }

  private static final class FetchMessagesTool implements ContextualTool {
    private static final ToolDefinition DEFINITION =
        new ToolDefinition(
            "fetch_messages",
            "读取当前会话中指定历史消息及有限上下文。结果可作为会话证据。",
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "ids", Map.of("type", "array", "items", Map.of("type", "string")),
                    "context", Map.of("type", "integer")),
                "required",
                List.of("ids"),
                "additionalProperties",
                false),
            ToolRisk.READ_ONLY,
            VERSION);

    @Override
    public ToolDefinition definition() {
      return DEFINITION;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
      return ToolResult.error(UNAVAILABLE);
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolInvocationContext context) {
      try {
        FetchRequest request = parseFetch(arguments);
        ConversationEvidenceScope scope = context.conversationEvidenceScope().orElseThrow();
        List<ConversationEvidenceMessage> sources = scope.fetch(request.references());
        if (sources.size() != request.references().size()) {
          return ToolResult.error(UNAVAILABLE);
        }
        List<ConversationEvidenceMessage> messages =
            request.context() == 0
                ? sources
                : scope.fetchWindow(request.references(), request.context());
        if (messages.size() > MAX_FETCH_IDS) {
          return ToolResult.error(UNAVAILABLE);
        }
        Set<ConversationEvidenceReference> sourceReferences =
            new LinkedHashSet<>(request.references());
        if (!messages.stream()
            .map(ConversationEvidenceMessage::reference)
            .collect(java.util.stream.Collectors.toSet())
            .containsAll(sourceReferences)) {
          return ToolResult.error(UNAVAILABLE);
        }
        return ToolResult.success(
            renderFetch(messages, sourceReferences, request.references().size()));
      } catch (IllegalArgumentException invalid) {
        return ToolResult.error(INVALID_ARGUMENT);
      } catch (RuntimeException unavailable) {
        return ToolResult.error(UNAVAILABLE);
      }
    }
  }

  private static final class SearchMessagesTool implements ContextualTool {
    private static final ToolDefinition DEFINITION =
        new ToolDefinition(
            "search_messages",
            "在当前会话历史中定位关键词，返回预览；需再用 fetch_messages 读取证据原文。",
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "query",
                    Map.of("type", "string"),
                    "role",
                    Map.of("type", "string", "enum", List.of("user", "assistant")),
                    "limit",
                    Map.of("type", "integer"),
                    "offset",
                    Map.of("type", "integer")),
                "required",
                List.of("query"),
                "additionalProperties",
                false),
            ToolRisk.READ_ONLY,
            VERSION);

    @Override
    public ToolDefinition definition() {
      return DEFINITION;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
      return ToolResult.error(UNAVAILABLE);
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolInvocationContext context) {
      try {
        SearchRequest request = parseSearch(arguments);
        ConversationEvidenceScope scope = context.conversationEvidenceScope().orElseThrow();
        ConversationEvidencePage page = scope.search(request.query());
        return ToolResult.success(renderSearch(page, request));
      } catch (IllegalArgumentException invalid) {
        return ToolResult.error(INVALID_ARGUMENT);
      } catch (RuntimeException unavailable) {
        return ToolResult.error(UNAVAILABLE);
      }
    }
  }

  private static FetchRequest parseFetch(Map<String, Object> arguments) {
    Object rawIds = arguments.get("ids");
    if (!(rawIds instanceof List<?> ids) || ids.isEmpty() || ids.size() > MAX_FETCH_IDS) {
      throw new IllegalArgumentException("ids 无效");
    }
    var references = new ArrayList<ConversationEvidenceReference>(ids.size());
    var unique = new LinkedHashSet<ConversationEvidenceReference>();
    for (Object rawId : ids) {
      if (!(rawId instanceof String value)) {
        throw new IllegalArgumentException("id 无效");
      }
      ConversationEvidenceReference reference = ConversationEvidenceReference.require(value);
      if (!unique.add(reference)) {
        throw new IllegalArgumentException("id 重复");
      }
      references.add(reference);
    }
    return new FetchRequest(
        List.copyOf(references), integer(arguments.get("context"), 0, 0, MAX_CONTEXT));
  }

  private static SearchRequest parseSearch(Map<String, Object> arguments) {
    Object rawQuery = arguments.get("query");
    if (!(rawQuery instanceof String value)) {
      throw new IllegalArgumentException("query 无效");
    }
    String query = value.strip();
    int codePoints = query.codePointCount(0, query.length());
    if (query.isBlank() || codePoints > MAX_QUERY_CODE_POINTS) {
      throw new IllegalArgumentException("query 超出范围");
    }
    var terms = new LinkedHashSet<String>();
    for (String term : UNICODE_WHITESPACE.split(query)) {
      String normalized = term.strip();
      if (!normalized.isBlank()) {
        terms.add(normalized);
      }
    }
    if (terms.isEmpty()) {
      throw new IllegalArgumentException("query 无效");
    }
    Optional<ConversationEvidenceRole> role = Optional.empty();
    if (arguments.containsKey("role")) {
      Object rawRole = arguments.get("role");
      if (!(rawRole instanceof String valueRole)) {
        throw new IllegalArgumentException("role 无效");
      }
      role = ConversationEvidenceRole.fromWireValue(valueRole);
      if (role.isEmpty()) {
        throw new IllegalArgumentException("role 无效");
      }
    }
    int limit = integer(arguments.get("limit"), 10, 1, MAX_SEARCH_LIMIT);
    int offset = integer(arguments.get("offset"), 0, 0, MAX_SEARCH_OFFSET);
    return new SearchRequest(
        List.copyOf(terms),
        new ConversationEvidenceSearchQuery(List.copyOf(terms), role, limit, offset));
  }

  private static int integer(Object raw, int defaultValue, int minimum, int maximum) {
    if (raw == null) {
      return defaultValue;
    }
    long value;
    if (raw instanceof Byte
        || raw instanceof Short
        || raw instanceof Integer
        || raw instanceof Long) {
      value = ((Number) raw).longValue();
    } else if (raw instanceof BigInteger integer) {
      value = integer.longValueExact();
    } else if (raw instanceof BigDecimal decimal) {
      value = decimal.longValueExact();
    } else {
      throw new IllegalArgumentException("数值必须是整数");
    }
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException("数值超出范围");
    }
    return Math.toIntExact(value);
  }

  private static String renderFetch(
      List<ConversationEvidenceMessage> messages,
      Set<ConversationEvidenceReference> sourceReferences,
      int matchedCount) {
    var output = baseEnvelope(messages.size(), matchedCount);
    output.append(",\"messages\":[");
    for (int index = 0; index < messages.size(); index++) {
      if (index > 0) {
        output.append(',');
      }
      ConversationEvidenceMessage message = messages.get(index);
      output
          .append("{\"id\":")
          .append(ToolCatalogJson.string(message.reference().externalId()))
          .append(",\"seq\":")
          .append(message.reference().sequence())
          .append(",\"role\":")
          .append(ToolCatalogJson.string(message.role().wireValue()))
          .append(",\"content\":")
          .append(ToolCatalogJson.string(message.content()))
          .append(",\"in_source_ref\":")
          .append(sourceReferences.contains(message.reference()))
          .append('}');
    }
    return finish(output);
  }

  private static String renderSearch(ConversationEvidencePage page, SearchRequest request) {
    var output =
        new StringBuilder("{\"count\":")
            .append(page.messages().size())
            .append(",\"matched_count\":")
            .append(page.matchedCount())
            .append(",\"limit\":")
            .append(request.query().limit())
            .append(",\"offset\":")
            .append(request.query().offset())
            .append(",\"has_more\":")
            .append(page.hasMore())
            .append(",\"next_offset\":");
    if (page.hasMore()) {
      output.append(request.query().offset() + page.messages().size());
    } else {
      output.append("null");
    }
    output
        .append(",\"citation_required\":true,\"citation_format\":")
        .append(ToolCatalogJson.string(CITATION_FORMAT))
        .append(",\"citation_rule\":")
        .append(ToolCatalogJson.string(CITATION_RULE))
        .append(",\"messages\":[");
    for (int index = 0; index < page.messages().size(); index++) {
      if (index > 0) {
        output.append(',');
      }
      ConversationEvidenceMessage message = page.messages().get(index);
      Preview preview = preview(message.content());
      output
          .append("{\"id\":")
          .append(ToolCatalogJson.string(message.reference().externalId()))
          .append(",\"source_ref\":")
          .append(ToolCatalogJson.string(message.reference().externalId()))
          .append(",\"seq\":")
          .append(message.reference().sequence())
          .append(",\"role\":")
          .append(ToolCatalogJson.string(message.role().wireValue()))
          .append(",\"matched_terms\":");
      stringArray(output, matchedTerms(message.content(), request.terms()));
      output
          .append(",\"preview\":")
          .append(ToolCatalogJson.string(preview.value()))
          .append(",\"preview_line_count\":")
          .append(preview.previewLineCount())
          .append(",\"total_line_count\":")
          .append(preview.totalLineCount())
          .append(",\"truncated\":")
          .append(preview.truncated())
          .append('}');
    }
    return finish(output);
  }

  private static StringBuilder baseEnvelope(int count, int matchedCount) {
    return new StringBuilder("{\"count\":")
        .append(count)
        .append(",\"matched_count\":")
        .append(matchedCount)
        .append(",\"citation_required\":true,\"citation_format\":")
        .append(ToolCatalogJson.string(CITATION_FORMAT))
        .append(",\"citation_rule\":")
        .append(ToolCatalogJson.string(CITATION_RULE));
  }

  private static String finish(StringBuilder output) {
    String rendered = output.append("]}").toString();
    if (rendered.codePointCount(0, rendered.length()) > MAX_PROJECTED_CODE_POINTS) {
      throw new IllegalStateException("会话证据投影超过预算");
    }
    return rendered;
  }

  private static List<String> matchedTerms(String content, List<String> terms) {
    String normalized = content.toLowerCase(java.util.Locale.ROOT);
    return terms.stream()
        .filter(term -> normalized.contains(term.toLowerCase(java.util.Locale.ROOT)))
        .toList();
  }

  private static void stringArray(StringBuilder output, List<String> values) {
    output.append('[');
    for (int index = 0; index < values.size(); index++) {
      if (index > 0) {
        output.append(',');
      }
      output.append(ToolCatalogJson.string(values.get(index)));
    }
    output.append(']');
  }

  private static Preview preview(String content) {
    String[] lines = content.split("\\R", -1);
    int total = lines.length;
    int count = Math.min(total, MAX_PREVIEW_LINES);
    String value = String.join("\n", java.util.Arrays.copyOf(lines, count));
    boolean truncated = total > MAX_PREVIEW_LINES;
    if (truncated) {
      value += "\n...[truncated]";
    }
    return new Preview(value, count, total, truncated);
  }

  private record FetchRequest(List<ConversationEvidenceReference> references, int context) {}

  private record SearchRequest(List<String> terms, ConversationEvidenceSearchQuery query) {}

  private record Preview(
      String value, int previewLineCount, int totalLineCount, boolean truncated) {}
}
