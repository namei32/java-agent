package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.evidence.ConversationEvidenceReference;
import io.namei.agent.kernel.evidence.ConversationEvidenceRole;
import io.namei.agent.kernel.evidence.ConversationEvidenceSearchQuery;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("failure")
class JdbcConversationEvidenceRepositoryTest {
  @TempDir Path tempDir;
  private SqliteSchemaInitializer schema;
  private JdbcConversationEvidenceRepository repository;

  @BeforeEach
  void setUp() throws Exception {
    schema = new SqliteSchemaInitializer(tempDir.resolve("sessions.db"), 5_000);
    schema.initialize();
    repository = new JdbcConversationEvidenceRepository(schema);
    insert("session-a", 0, "user", "first cache note");
    insert("session-a", 1, "assistant", "Phase completed");
    insert("session-a", 2, "system", "must not be exposed");
    insert("session-a", 3, "user", "支付已确认");
    insert("session-a", 4, "assistant", "cache payment answer");
    insert("session-b", 0, "user", "cache from another session");
  }

  @Test
  void fetchPreservesRequestedOrderAndCannotCrossCurrentSession() {
    var messages =
        repository.fetch(
            "session-a",
            List.of(new ConversationEvidenceReference(4), new ConversationEvidenceReference(0)));

    assertThat(messages)
        .extracting(message -> message.reference().externalId())
        .containsExactly("msg-v1:4", "msg-v1:0");
    assertThat(repository.fetch("session-a", List.of(new ConversationEvidenceReference(99))))
        .isEmpty();
    assertThat(repository.fetch("session-b", List.of(new ConversationEvidenceReference(0))))
        .extracting(message -> message.content())
        .containsExactly("cache from another session");
  }

  @Test
  void fetchWindowOrdersBySequenceAndExcludesSystemRows() {
    var messages =
        repository.fetchWindow("session-a", List.of(new ConversationEvidenceReference(3)), 3);

    assertThat(messages)
        .extracting(message -> message.reference().sequence())
        .containsExactly(0L, 1L, 3L, 4L);
    assertThat(messages)
        .extracting(message -> message.content())
        .doesNotContain("must not be exposed");
  }

  @Test
  void searchUsesOrTermsRolePagingAndCurrentSessionOnly() {
    var page =
        repository.search(
            "session-a",
            new ConversationEvidenceSearchQuery(List.of("phase", "支付"), Optional.empty(), 2, 0));

    assertThat(page.matchedCount()).isEqualTo(2);
    assertThat(page.hasMore()).isFalse();
    assertThat(page.messages())
        .extracting(message -> message.reference().sequence())
        .containsExactly(3L, 1L);

    var userOnly =
        repository.search(
            "session-a",
            new ConversationEvidenceSearchQuery(
                List.of("cache"), Optional.of(ConversationEvidenceRole.USER), 1, 0));
    assertThat(userOnly.matchedCount()).isEqualTo(1);
    assertThat(userOnly.messages())
        .extracting(message -> message.reference().sequence())
        .containsExactly(0L);

    var paged =
        repository.search(
            "session-a",
            new ConversationEvidenceSearchQuery(List.of("cache"), Optional.empty(), 1, 1));
    assertThat(paged.matchedCount()).isEqualTo(2);
    assertThat(paged.hasMore()).isFalse();
    assertThat(paged.messages())
        .extracting(message -> message.reference().sequence())
        .containsExactly(0L);
  }

  @Test
  void searchFailsClosedWhenTheBoundedCandidateScanWouldBeExceeded() throws Exception {
    insertMany("candidate-session", JdbcConversationEvidenceRepository.MAX_SEARCH_CANDIDATES + 1);

    assertThatThrownBy(
            () ->
                repository.search(
                    "candidate-session",
                    new ConversationEvidenceSearchQuery(List.of("absent"), Optional.empty(), 1, 0)))
        .isInstanceOf(SqliteRepositoryException.class)
        .hasMessage("会话证据候选过多");
  }

  @Test
  void storeFailuresStayBehindAdapterBoundary() {
    var unavailable =
        new JdbcConversationEvidenceRepository(
            new SqliteSchemaInitializer(tempDir.resolve("missing/parent/sessions.db"), 5_000));

    assertThatThrownBy(
            () -> unavailable.fetch("session-a", List.of(new ConversationEvidenceReference(0))))
        .isInstanceOf(SqliteRepositoryException.class)
        .hasMessage("读取会话证据失败");
  }

  private void insert(String session, long sequence, String role, String content) throws Exception {
    try (var connection = schema.openConnection();
        var sessionInsert =
            connection.prepareStatement(
                """
                INSERT OR IGNORE INTO sessions (
                  key, created_at, updated_at, last_consolidated, metadata,
                  last_user_at, last_proactive_at, next_seq
                ) VALUES (?, 't', 't', 0, '{}', NULL, NULL, 0)
                """);
        var messageInsert =
            connection.prepareStatement(
                """
                INSERT INTO messages (id, session_key, seq, role, content, tool_chain, extra, ts)
                VALUES (?, ?, ?, ?, ?, 'internal_tool_chain', '{}', 't')
                """)) {
      sessionInsert.setString(1, session);
      sessionInsert.executeUpdate();
      messageInsert.setString(1, session + ":" + sequence);
      messageInsert.setString(2, session);
      messageInsert.setLong(3, sequence);
      messageInsert.setString(4, role);
      messageInsert.setString(5, content);
      messageInsert.executeUpdate();
    }
  }

  private void insertMany(String session, int count) throws Exception {
    try (var connection = schema.openConnection();
        var sessionInsert =
            connection.prepareStatement(
                """
                INSERT OR IGNORE INTO sessions (
                  key, created_at, updated_at, last_consolidated, metadata,
                  last_user_at, last_proactive_at, next_seq
                ) VALUES (?, 't', 't', 0, '{}', NULL, NULL, 0)
                """);
        var messageInsert =
            connection.prepareStatement(
                """
                INSERT INTO messages (id, session_key, seq, role, content, tool_chain, extra, ts)
                VALUES (?, ?, ?, 'user', 'candidate', 'internal_tool_chain', '{}', 't')
                """)) {
      sessionInsert.setString(1, session);
      sessionInsert.executeUpdate();
      for (int sequence = 0; sequence < count; sequence++) {
        messageInsert.setString(1, session + ":" + sequence);
        messageInsert.setString(2, session);
        messageInsert.setInt(3, sequence);
        messageInsert.addBatch();
      }
      messageInsert.executeBatch();
    }
  }
}
