package io.namei.agent.adapter.sqlite;

import io.namei.agent.kernel.evidence.ConversationEvidenceMessage;
import io.namei.agent.kernel.evidence.ConversationEvidencePage;
import io.namei.agent.kernel.evidence.ConversationEvidenceReference;
import io.namei.agent.kernel.evidence.ConversationEvidenceRole;
import io.namei.agent.kernel.evidence.ConversationEvidenceSearchQuery;
import io.namei.agent.kernel.port.ConversationEvidencePort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 由 SQLite 支持、限定在已绑定 Session Key 内的只读证据投影。 */
public final class JdbcConversationEvidenceRepository implements ConversationEvidencePort {
  static final int MAX_SEARCH_CANDIDATES = 2_048;

  private final SqliteSchemaInitializer schema;

  public JdbcConversationEvidenceRepository(SqliteSchemaInitializer schema) {
    this.schema = Objects.requireNonNull(schema, "schema");
  }

  @Override
  public List<ConversationEvidenceMessage> fetch(
      String sessionId, List<ConversationEvidenceReference> references) {
    requireRequest(sessionId, references);
    var found = new ArrayList<ConversationEvidenceMessage>();
    try (var connection = schema.openConnection();
        var statement =
            connection.prepareStatement(
                """
                SELECT seq, role, content
                FROM messages
                WHERE session_key = ? AND seq = ? AND role IN ('user', 'assistant')
                """)) {
      for (ConversationEvidenceReference reference : references) {
        statement.setString(1, sessionId);
        statement.setLong(2, reference.sequence());
        try (var rows = statement.executeQuery()) {
          if (rows.next()) {
            readMessage(rows).ifPresent(found::add);
          }
        }
      }
      return List.copyOf(found);
    } catch (SQLException exception) {
      throw new SqliteRepositoryException("读取会话证据失败", exception);
    }
  }

  @Override
  public List<ConversationEvidenceMessage> fetchWindow(
      String sessionId, List<ConversationEvidenceReference> sourceReferences, int context) {
    requireRequest(sessionId, sourceReferences);
    if (context < 0 || context > 10) {
      throw new IllegalArgumentException("会话证据上下文窗口无效");
    }
    if (context == 0) {
      return fetch(sessionId, sourceReferences);
    }
    Map<Long, ConversationEvidenceMessage> bySequence = new LinkedHashMap<>();
    try (var connection = schema.openConnection();
        var statement =
            connection.prepareStatement(
                """
                SELECT seq, role, content
                FROM messages
                WHERE session_key = ? AND seq BETWEEN ? AND ? AND role IN ('user', 'assistant')
                ORDER BY seq ASC
                """)) {
      for (ConversationEvidenceReference reference : sourceReferences) {
        statement.setString(1, sessionId);
        statement.setLong(2, Math.max(0, reference.sequence() - context));
        statement.setLong(3, upperBound(reference.sequence(), context));
        try (var rows = statement.executeQuery()) {
          while (rows.next()) {
            readMessage(rows)
                .ifPresent(
                    message -> bySequence.putIfAbsent(message.reference().sequence(), message));
          }
        }
      }
    } catch (SQLException exception) {
      throw new SqliteRepositoryException("读取会话证据失败", exception);
    }
    return bySequence.values().stream()
        .sorted(Comparator.comparingLong(message -> message.reference().sequence()))
        .toList();
  }

  @Override
  public ConversationEvidencePage search(String sessionId, ConversationEvidenceSearchQuery query) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(query, "query");
    var candidates = new ArrayList<ConversationEvidenceMessage>();
    try (var connection = schema.openConnection();
        var statement =
            connection.prepareStatement(
                """
                SELECT seq, role, content
                FROM messages
                WHERE session_key = ?
                  AND role IN ('user', 'assistant')
                  AND (? IS NULL OR role = ?)
                ORDER BY seq DESC
                LIMIT ?
                """)) {
      statement.setString(1, sessionId);
      String role = query.role().map(ConversationEvidenceRole::wireValue).orElse(null);
      statement.setString(2, role);
      statement.setString(3, role);
      statement.setInt(4, MAX_SEARCH_CANDIDATES + 1);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          readMessage(rows).ifPresent(candidates::add);
        }
      }
    } catch (SQLException exception) {
      throw new SqliteRepositoryException("读取会话证据失败", exception);
    }
    if (candidates.size() > MAX_SEARCH_CANDIDATES) {
      throw new SqliteRepositoryException("会话证据候选过多");
    }
    List<ConversationEvidenceMessage> matching =
        candidates.stream().filter(message -> matches(message.content(), query.terms())).toList();
    int start = Math.min(query.offset(), matching.size());
    int end = Math.min(start + query.limit(), matching.size());
    return new ConversationEvidencePage(
        matching.subList(start, end), matching.size(), end < matching.size());
  }

  private static void requireRequest(
      String sessionId, List<ConversationEvidenceReference> references) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(references, "references");
    references.forEach(reference -> Objects.requireNonNull(reference, "reference"));
  }

  private static long upperBound(long sequence, int context) {
    try {
      return Math.addExact(sequence, context);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  private static java.util.Optional<ConversationEvidenceMessage> readMessage(ResultSet rows)
      throws SQLException {
    var role = ConversationEvidenceRole.fromWireValue(rows.getString("role"));
    String content = rows.getString("content");
    if (role.isEmpty() || content == null || content.isBlank()) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(
        new ConversationEvidenceMessage(
            new ConversationEvidenceReference(rows.getLong("seq")), role.orElseThrow(), content));
  }

  private static boolean matches(String content, List<String> terms) {
    String normalized = content.toLowerCase(Locale.ROOT);
    return terms.stream().anyMatch(term -> normalized.contains(term.toLowerCase(Locale.ROOT)));
  }
}
