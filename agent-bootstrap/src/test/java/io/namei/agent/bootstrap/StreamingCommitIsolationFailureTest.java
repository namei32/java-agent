package io.namei.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.adapter.sqlite.JdbcSessionRepository;
import io.namei.agent.adapter.sqlite.SqliteSchemaInitializer;
import io.namei.agent.application.ChatCommand;
import io.namei.agent.application.ChatService;
import io.namei.agent.application.SessionExecutionGate;
import io.namei.agent.application.TurnCancellationSource;
import io.namei.agent.kernel.concurrent.CancellationSignal;
import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.TurnCancelledException;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.ChatModelStreamObserver;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("failure")
class StreamingCommitIsolationFailureTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);

  @TempDir Path tempDir;

  @Test
  void cancellationAfterARealDeltaLeavesNoHalfTurnInSqlite() throws Exception {
    var sqlite = sqlite("cancelled.db");
    var cancellation = new TurnCancellationSource();
    var deltas = new ArrayList<String>();
    var service =
        service(
            sqlite.repository(),
            streamingModel(
                (observer, ignored) -> {
                  observer.onContentDelta("部分");
                  return new ChatModelResponse("部分回答");
                }));

    assertThatThrownBy(
            () ->
                service.chat(
                    new ChatCommand("cancelled", "问题"),
                    cancellation.token(),
                    delta -> {
                      deltas.add(delta);
                      cancellation.cancel();
                    }))
        .isInstanceOf(TurnCancelledException.class);

    assertThat(deltas).containsExactly("部分");
    assertEmpty(sqlite, "cancelled");
  }

  @Test
  void invalidCompletionAfterARealDeltaLeavesNoHalfTurnInSqlite() throws Exception {
    var sqlite = sqlite("invalid.db");
    var deltas = new ArrayList<String>();
    var service =
        service(
            sqlite.repository(),
            streamingModel(
                (observer, ignored) -> {
                  observer.onContentDelta("临时预览");
                  return null;
                }));

    assertThatThrownBy(
            () ->
                service.chat(
                    new ChatCommand("invalid", "问题"),
                    new TurnCancellationSource().token(),
                    deltas::add))
        .isInstanceOf(InvalidModelResponseException.class);

    assertThat(deltas).containsExactly("临时预览");
    assertEmpty(sqlite, "invalid");
  }

  private SqliteFixture sqlite(String databaseName) {
    var schema = new SqliteSchemaInitializer(tempDir.resolve(databaseName), 5_000);
    schema.initialize();
    return new SqliteFixture(schema, new JdbcSessionRepository(schema));
  }

  private static ChatService service(JdbcSessionRepository repository, ChatModelPort model) {
    return new ChatService(
        repository,
        model,
        new ConversationHistorySelector(),
        new HistoryLimits(40, 100_000),
        directGate(),
        "系统提示",
        CLOCK);
  }

  private static ChatModelPort streamingModel(StreamingBehavior behavior) {
    return new ChatModelPort() {
      @Override
      public ChatModelResponse generate(ChatModelRequest request) {
        throw new AssertionError("流式路径不得回退到同步调用");
      }

      @Override
      public ChatModelResponse generate(
          ChatModelRequest request,
          ChatModelStreamObserver observer,
          CancellationSignal cancellation) {
        return behavior.generate(observer, cancellation);
      }
    };
  }

  private static SessionExecutionGate directGate() {
    return new SessionExecutionGate() {
      @Override
      public <T> T execute(String sessionId, Supplier<T> action) {
        return action.get();
      }
    };
  }

  private static void assertEmpty(SqliteFixture sqlite, String sessionId) throws Exception {
    var snapshot = sqlite.repository().load(sessionId);
    assertThat(snapshot.messages()).isEmpty();
    assertThat(snapshot.nextSequence()).isZero();
    try (var connection = sqlite.schema().openConnection();
        var statement = connection.createStatement()) {
      try (var sessions = statement.executeQuery("SELECT COUNT(*) FROM sessions")) {
        assertThat(sessions.next()).isTrue();
        assertThat(sessions.getInt(1)).isZero();
      }
      try (var messages = statement.executeQuery("SELECT COUNT(*) FROM messages")) {
        assertThat(messages.next()).isTrue();
        assertThat(messages.getInt(1)).isZero();
      }
    }
  }

  private record SqliteFixture(SqliteSchemaInitializer schema, JdbcSessionRepository repository) {}

  @FunctionalInterface
  private interface StreamingBehavior {
    ChatModelResponse generate(
        ChatModelStreamObserver observer, CancellationSignal cancellationSignal);
  }
}
