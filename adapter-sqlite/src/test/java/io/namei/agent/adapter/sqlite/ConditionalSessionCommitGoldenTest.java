package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PersistedTurn;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ConditionalSessionCommitGoldenTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final OffsetDateTime USER_AT = OffsetDateTime.parse("2026-07-19T08:00:00+08:00");

  @TempDir Path tempDir;

  @Test
  void executesEveryVersionedConditionalSessionFixtureCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("tools/pending-operation-v1.json").toFile());
    assertThat(fixture.path("cases").size()).isEqualTo(34);
    for (JsonNode testCase : fixture.path("cases")) {
      String id = testCase.path("id").asText();
      if (id.startsWith("session-conditional-")) {
        verify(id);
      }
    }
  }

  private void verify(String id) throws Exception {
    Path database = tempDir.resolve(id).resolve("sessions.db");
    Files.createDirectories(database.getParent());
    SqliteSchemaInitializer schema = new SqliteSchemaInitializer(database, 5_000);
    schema.initialize();
    JdbcSessionRepository repository = new JdbcSessionRepository(schema);
    switch (id) {
      case "session-conditional-append-matches-next-sequence" -> {
        assertThat(repository.appendTurnIfNextSequence("session", 0, turn())).isTrue();
        assertThat(repository.load("session").nextSequence()).isEqualTo(2);
      }
      case "session-conditional-append-rejects-stale-sequence" -> {
        assertThat(repository.appendTurnIfNextSequence("session", 0, turn())).isTrue();
        assertThat(repository.appendTurnIfNextSequence("session", 0, turn())).isFalse();
        assertThat(repository.load("session").messages()).hasSize(2);
      }
      default -> throw new AssertionError("未知条件 Session Fixture Case: " + id);
    }
  }

  private static PersistedTurn turn() {
    return new PersistedTurn(
        new ChatMessage(MessageRole.USER, "问题"),
        USER_AT,
        new ChatMessage(MessageRole.ASSISTANT, "回答"),
        USER_AT.plusSeconds(1));
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
