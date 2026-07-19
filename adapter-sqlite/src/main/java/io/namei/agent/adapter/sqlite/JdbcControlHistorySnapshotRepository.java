package io.namei.agent.adapter.sqlite;

import io.namei.agent.kernel.control.HistoryDetailItem;
import io.namei.agent.kernel.control.HistoryDetailPage;
import io.namei.agent.kernel.control.HistoryDetailReadRequest;
import io.namei.agent.kernel.control.HistoryScopeCapability;
import io.namei.agent.kernel.control.HistorySnapshotUnavailableException;
import io.namei.agent.kernel.control.HistoryVisibleRole;
import io.namei.agent.kernel.port.ControlHistorySnapshotPort;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fixed-query, read-only, zero-content metadata projection for trusted current-Scope capabilities.
 */
public final class JdbcControlHistorySnapshotRepository implements ControlHistorySnapshotPort {
  static final int MAXIMUM_CANDIDATES = 1_024;
  private static final Duration RETENTION = Duration.ofHours(24);
  private static final Set<String> SESSION_COLUMNS =
      Set.of(
          "key",
          "created_at",
          "updated_at",
          "last_consolidated",
          "metadata",
          "last_user_at",
          "last_proactive_at",
          "next_seq");
  private static final Set<String> MESSAGE_COLUMNS =
      Set.of("id", "session_key", "seq", "role", "content", "tool_chain", "extra", "ts");

  private final SqliteSchemaInitializer schema;
  private final Map<HistoryScopeCapability, String> sessionsByScope;

  public JdbcControlHistorySnapshotRepository(
      SqliteSchemaInitializer schema, Map<HistoryScopeCapability, String> sessionsByScope) {
    this.schema = Objects.requireNonNull(schema, "schema");
    this.sessionsByScope = requireBoundSessions(sessionsByScope);
  }

  @Override
  public HistoryDetailPage read(HistoryScopeCapability scope, HistoryDetailReadRequest request) {
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(request, "request");
    String sessionKey = sessionsByScope.get(scope);
    if (sessionKey == null) {
      throw new HistorySnapshotUnavailableException();
    }
    try (Connection connection = schema.openConnection()) {
      try (var statement = connection.createStatement()) {
        statement.execute("PRAGMA query_only = ON");
      }
      requireCurrentSessionSchema(connection);
      return readPage(connection, sessionKey, request);
    } catch (HistorySnapshotUnavailableException unavailable) {
      throw unavailable;
    } catch (SQLException | RuntimeException unavailable) {
      throw new HistorySnapshotUnavailableException();
    }
  }

  private static HistoryDetailPage readPage(
      Connection connection, String sessionKey, HistoryDetailReadRequest request)
      throws SQLException {
    var candidates = new ArrayList<Candidate>();
    try (var statement =
        connection.prepareStatement(
            """
            SELECT messages.role, messages.ts, messages.seq
            FROM messages
            INNER JOIN sessions ON sessions.key = messages.session_key
            WHERE sessions.key = ?
            ORDER BY messages.seq DESC
            LIMIT ?
            """)) {
      statement.setString(1, sessionKey);
      statement.setInt(2, MAXIMUM_CANDIDATES + 1);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          if (candidates.size() >= MAXIMUM_CANDIDATES) {
            throw new HistorySnapshotUnavailableException();
          }
          candidates.add(
              readCandidate(rows.getString("role"), rows.getString("ts"), rows.getLong("seq")));
        }
      }
    }

    Instant earliest = request.observedAt().minus(RETENTION);
    List<HistoryDetailItem> approved =
        candidates.stream()
            .filter(candidate -> !candidate.occurredAt().isBefore(earliest))
            .filter(candidate -> !candidate.occurredAt().isAfter(request.observedAt()))
            .sorted(
                Comparator.comparing(Candidate::occurredAt)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(Candidate::sequence).reversed()))
            .map(candidate -> new HistoryDetailItem(candidate.role(), candidate.occurredAt()))
            .toList();
    int start = Math.min(request.offset(), approved.size());
    int end = Math.min(start + request.pageSize(), approved.size());
    return new HistoryDetailPage(approved.subList(start, end), end < approved.size());
  }

  private static Candidate readCandidate(String role, String timestamp, long sequence) {
    if (sequence < 0 || timestamp == null || role == null) {
      throw new HistorySnapshotUnavailableException();
    }
    HistoryVisibleRole visibleRole =
        switch (role) {
          case "user" -> HistoryVisibleRole.USER;
          case "assistant" -> HistoryVisibleRole.ASSISTANT;
          default -> throw new HistorySnapshotUnavailableException();
        };
    try {
      return new Candidate(visibleRole, OffsetDateTime.parse(timestamp).toInstant(), sequence);
    } catch (RuntimeException invalid) {
      throw new HistorySnapshotUnavailableException();
    }
  }

  private static void requireCurrentSessionSchema(Connection connection) throws SQLException {
    requireExactColumns(connection, "sessions", SESSION_COLUMNS);
    requireExactColumns(connection, "messages", MESSAGE_COLUMNS);
  }

  private static void requireExactColumns(Connection connection, String table, Set<String> expected)
      throws SQLException {
    var actual = new HashSet<String>();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rows.next()) {
        actual.add(rows.getString("name"));
      }
    }
    if (!actual.equals(expected)) {
      throw new SQLException("控制历史 Session Schema 不兼容");
    }
  }

  private static Map<HistoryScopeCapability, String> requireBoundSessions(
      Map<HistoryScopeCapability, String> sessionsByScope) {
    Map<HistoryScopeCapability, String> bindings =
        Map.copyOf(Objects.requireNonNull(sessionsByScope));
    bindings.forEach(
        (scope, sessionKey) -> {
          Objects.requireNonNull(scope, "scope");
          if (sessionKey == null || sessionKey.isBlank()) {
            throw new IllegalArgumentException("控制历史 Scope 绑定无效");
          }
        });
    return bindings;
  }

  private record Candidate(HistoryVisibleRole role, Instant occurredAt, long sequence) {}
}
