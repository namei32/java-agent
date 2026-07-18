package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PendingTurnAnchor;
import io.namei.agent.kernel.model.PersistedTurn;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("failure")
class PendingTurnAnchorStoreTest {
  private static final OffsetDateTime USER_AT = OffsetDateTime.parse("2026-07-19T08:00:00+08:00");
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;
  private SqliteSchemaInitializer schema;
  private JdbcSessionRepository repository;

  @BeforeEach
  void setUp() {
    schema = new SqliteSchemaInitializer(tempDir.resolve("sessions.db"), 5_000);
    schema.initialize();
    repository = new JdbcSessionRepository(schema);
  }

  @Test
  void atomicallyPersistsTheInitialPendingTurnAndItsAnchor() throws Exception {
    PendingTurnAnchor anchor = anchor("AQEBAQEBAQEBAQEBAQEBAQ", 0);

    assertThat(repository.appendPendingTurnIfNextSequence(pendingTurn(), anchor)).isTrue();
    assertThat(repository.load("anchor-session").nextSequence()).isEqualTo(2);
    assertThat(repository.load("anchor-session").messages())
        .containsExactly(
            new ChatMessage(MessageRole.USER, "请求执行受控操作"),
            new ChatMessage(MessageRole.ASSISTANT, "该操作等待审批。"));
    assertThat(repository.findPendingTurnAnchor(anchor.operationReference())).contains(anchor);
    try (Connection connection = schema.openConnection();
        var statement =
            connection.prepareStatement(
                "SELECT operation_ref, session_key, created_next_sequence, resume_next_sequence, state, "
                    + "projection_version FROM pending_turn_anchors")) {
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo(anchor.operationReference());
        assertThat(rows.getString(2)).isEqualTo("anchor-session");
        assertThat(rows.getLong(3)).isZero();
        assertThat(rows.getLong(4)).isEqualTo(2);
        assertThat(rows.getString(5)).isEqualTo("PENDING_APPROVAL");
        assertThat(rows.getString(6)).isEqualTo("pending-projection-v1");
        assertThat(rows.next()).isFalse();
      }
    }
  }

  @Test
  void rejectsAStaleCursorWithoutWritingMessagesOrAnAnchor() throws Exception {
    repository.appendTurn("anchor-session", pendingTurn());
    PendingTurnAnchor anchor = anchor("AgICAgICAgICAgICAgICAg", 0);

    assertThat(repository.appendPendingTurnIfNextSequence(pendingTurn(), anchor)).isFalse();
    assertThat(repository.load("anchor-session").messages()).hasSize(2);
    assertThat(repository.findPendingTurnAnchor(anchor.operationReference())).isEmpty();
  }

  @Test
  void rollsBackTheEntireInitialPendingTurnWhenAnchorInsertionFails() throws Exception {
    try (Connection connection = schema.openConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TRIGGER fail_anchor BEFORE INSERT ON pending_turn_anchors
          BEGIN SELECT RAISE(ABORT, 'anchor-failure'); END
          """);
    }

    assertThatThrownBy(
            () ->
                repository.appendPendingTurnIfNextSequence(
                    pendingTurn(), anchor("AwMDAwMDAwMDAwMDAwMDAw", 0)))
        .isInstanceOf(SqliteRepositoryException.class)
        .hasMessageContaining("Pending Turn");
    assertThat(repository.load("anchor-session").messages()).isEmpty();
    assertThat(repository.findPendingTurnAnchor("AwMDAwMDAwMDAwMDAwMDAw")).isEmpty();
  }

  @Test
  @Tag("compat")
  void executesEveryVersionedAnchorStoreFixtureCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("tools/pending-operation-v1.json").toFile());
    assertThat(fixture.path("cases").size()).isEqualTo(44);
    for (JsonNode testCase : fixture.path("cases")) {
      String id = testCase.path("id").asText();
      if (id.startsWith("anchor-store-")) {
        verifyFixture(id);
      }
    }
  }

  private void verifyFixture(String id) throws Exception {
    schema = new SqliteSchemaInitializer(tempDir.resolve(id + ".db"), 5_000);
    schema.initialize();
    repository = new JdbcSessionRepository(schema);
    switch (id) {
      case "anchor-store-persists-atomically" ->
          assertThat(
                  repository.appendPendingTurnIfNextSequence(
                      pendingTurn(), anchor("BAQEBAQEBAQEBAQEBAQEBA", 0)))
              .isTrue();
      case "anchor-store-rejects-stale-cursor" -> {
        repository.appendTurn("anchor-session", pendingTurn());
        assertThat(
                repository.appendPendingTurnIfNextSequence(
                    pendingTurn(), anchor("BQUFBQUFBQUFBQUFBQUFBQ", 0)))
            .isFalse();
      }
      case "anchor-store-rolls-back-anchor-insert-failure" -> {
        try (Connection connection = schema.openConnection();
            var statement = connection.createStatement()) {
          statement.execute(
              """
              CREATE TRIGGER fail_anchor BEFORE INSERT ON pending_turn_anchors
              BEGIN SELECT RAISE(ABORT, 'anchor-failure'); END
              """);
        }
        assertThatThrownBy(
                () ->
                    repository.appendPendingTurnIfNextSequence(
                        pendingTurn(), anchor("BgYGBgYGBgYGBgYGBgYGBg", 0)))
            .isInstanceOf(SqliteRepositoryException.class);
      }
      default -> throw new AssertionError("未知 Anchor Store Fixture Case: " + id);
    }
  }

  private static PendingTurnAnchor anchor(String reference, long createdNextSequence) {
    return PendingTurnAnchor.pending(
        reference, "anchor-session", createdNextSequence, "pending-projection-v1");
  }

  private static PersistedTurn pendingTurn() {
    return new PersistedTurn(
        new ChatMessage(MessageRole.USER, "请求执行受控操作"),
        USER_AT,
        new ChatMessage(MessageRole.ASSISTANT, "该操作等待审批。"),
        USER_AT.plusSeconds(1));
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
