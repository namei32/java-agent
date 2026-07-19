package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.control.HistoryDetailPage;
import io.namei.agent.kernel.control.HistoryDetailReadRequest;
import io.namei.agent.kernel.control.HistoryDetailRef;
import io.namei.agent.kernel.control.HistoryScopeCapability;
import io.namei.agent.kernel.control.HistorySnapshotUnavailableException;
import io.namei.agent.kernel.control.HistoryVisibleRole;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("failure")
class JdbcControlHistorySnapshotRepositoryTest {
  private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
  private static final HistoryScopeCapability CURRENT_SCOPE =
      HistoryScopeCapability.fromTrustedDigest("a".repeat(64));
  private static final HistoryScopeCapability OTHER_SCOPE =
      HistoryScopeCapability.fromTrustedDigest("b".repeat(64));

  @TempDir Path tempDir;
  private SqliteSchemaInitializer schema;
  private JdbcControlHistorySnapshotRepository repository;

  @BeforeEach
  void setUp() throws Exception {
    schema = new SqliteSchemaInitializer(tempDir.resolve("sessions.db"), 5_000);
    schema.initialize();
    insert("current-session", 0, "user", "private first body", NOW.minusSeconds(10));
    insert("current-session", 1, "assistant", "private answer body", NOW.minusSeconds(5));
    insert("current-session", 2, "user", "old private body", NOW.minusSeconds(86_401));
    insert("other-session", 0, "user", "other scope body", NOW.minusSeconds(1));
    repository =
        new JdbcControlHistorySnapshotRepository(schema, Map.of(CURRENT_SCOPE, "current-session"));
  }

  @Test
  void projectsOnlyCurrentScopeRecentRoleAndTimeWithoutContentOrWrites() throws Exception {
    int messagesBefore = messageCount();

    HistoryDetailPage page = repository.read(CURRENT_SCOPE, request(10));

    assertThat(page.hasMore()).isFalse();
    assertThat(page.items())
        .extracting(item -> item.role())
        .containsExactly(HistoryVisibleRole.ASSISTANT, HistoryVisibleRole.USER);
    assertThat(page.items())
        .extracting(item -> item.occurredAt())
        .containsExactly(NOW.minusSeconds(5), NOW.minusSeconds(10));
    assertThat(page.toString())
        .doesNotContain(
            "private first body",
            "private answer body",
            "old private body",
            "other scope body",
            "current-session");
    assertThat(messageCount()).isEqualTo(messagesBefore);
  }

  @Test
  void boundsThePageAndFailsClosedForUnknownScope() {
    assertThat(repository.read(CURRENT_SCOPE, request(1)).items()).hasSize(1);
    assertThat(repository.read(CURRENT_SCOPE, request(1)).hasMore()).isTrue();
    assertThatThrownBy(() -> repository.read(OTHER_SCOPE, request(10)))
        .isInstanceOf(HistorySnapshotUnavailableException.class);
  }

  @Test
  void failsClosedForAnUnknownRole() throws Exception {
    insert("current-session", 3, "system", "not visible", NOW.minusSeconds(2));

    assertThatThrownBy(() -> repository.read(CURRENT_SCOPE, request(10)))
        .isInstanceOf(HistorySnapshotUnavailableException.class);
  }

  @Test
  void failsClosedForAnInvalidTimestampOrUnexpectedSchemaColumn() throws Exception {
    try (var connection = schema.openConnection();
        var update = connection.prepareStatement("UPDATE messages SET ts = ? WHERE seq = ?")) {
      update.setString(1, "not-a-timestamp");
      update.setLong(2, 1);
      update.executeUpdate();
    }

    assertThatThrownBy(() -> repository.read(CURRENT_SCOPE, request(10)))
        .isInstanceOf(HistorySnapshotUnavailableException.class);

    try (var connection = schema.openConnection();
        var update = connection.prepareStatement("UPDATE messages SET ts = ? WHERE seq = ?")) {
      update.setString(1, NOW.minusSeconds(5).toString());
      update.setLong(2, 1);
      update.executeUpdate();
    }
    try (var connection = schema.openConnection();
        var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE messages ADD COLUMN unapproved_column TEXT");
    }

    assertThatThrownBy(() -> repository.read(CURRENT_SCOPE, request(10)))
        .isInstanceOf(HistorySnapshotUnavailableException.class);
  }

  @Test
  void failsClosedRatherThanScanningAnUnboundedCurrentScope() throws Exception {
    insertMany("current-session", 10, 1_022);

    assertThatThrownBy(() -> repository.read(CURRENT_SCOPE, request(10)))
        .isInstanceOf(HistorySnapshotUnavailableException.class);
  }

  private HistoryDetailReadRequest request(int pageSize) {
    return new HistoryDetailReadRequest(HistoryDetailRef.fromBytes(new byte[16]), pageSize, NOW);
  }

  private void insert(String session, long sequence, String role, String content, Instant timestamp)
      throws Exception {
    try (var connection = schema.openConnection();
        var sessionInsert =
            connection.prepareStatement(
                """
                INSERT OR IGNORE INTO sessions (
                  key, created_at, updated_at, last_consolidated, metadata,
                  last_user_at, last_proactive_at, next_seq
                ) VALUES (?, ?, ?, 0, '{}', NULL, NULL, 0)
                """);
        var messageInsert =
            connection.prepareStatement(
                """
                INSERT INTO messages (id, session_key, seq, role, content, tool_chain, extra, ts)
                VALUES (?, ?, ?, ?, ?, NULL, '{}', ?)
                """)) {
      sessionInsert.setString(1, session);
      sessionInsert.setString(2, timestamp.toString());
      sessionInsert.setString(3, timestamp.toString());
      sessionInsert.executeUpdate();
      messageInsert.setString(1, session + ":" + sequence);
      messageInsert.setString(2, session);
      messageInsert.setLong(3, sequence);
      messageInsert.setString(4, role);
      messageInsert.setString(5, content);
      messageInsert.setString(6, timestamp.toString());
      messageInsert.executeUpdate();
    }
  }

  private int messageCount() throws Exception {
    try (var connection = schema.openConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT COUNT(*) FROM messages")) {
      assertThat(rows.next()).isTrue();
      return rows.getInt(1);
    }
  }

  private void insertMany(String session, int firstSequence, int count) throws Exception {
    try (var connection = schema.openConnection();
        var insert =
            connection.prepareStatement(
                """
                INSERT INTO messages (id, session_key, seq, role, content, tool_chain, extra, ts)
                VALUES (?, ?, ?, 'user', 'private bounded body', NULL, '{}', ?)
                """)) {
      for (int offset = 0; offset < count; offset++) {
        int sequence = firstSequence + offset;
        insert.setString(1, session + ":" + sequence);
        insert.setString(2, session);
        insert.setInt(3, sequence);
        insert.setString(4, NOW.minusSeconds(20).toString());
        insert.addBatch();
      }
      insert.executeBatch();
    }
  }
}
