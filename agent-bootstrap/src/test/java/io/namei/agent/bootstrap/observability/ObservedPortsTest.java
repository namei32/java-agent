package io.namei.agent.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PendingTurnAnchor;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.SessionRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ObservedPortsTest {
  @Test
  void modelSuccessLogContainsStableFieldsWithoutPromptOrResponse() {
    ChatModelPort successful =
        request -> new io.namei.agent.kernel.model.ChatModelResponse("SECRET-RESPONSE");

    String log =
        captureSuccess(
            ObservedChatModelPort.class,
            () ->
                new ObservedChatModelPort(successful, "test-model")
                    .generate(
                        new ChatModelRequest(
                            List.of(new ChatMessage(MessageRole.USER, "SECRET-PROMPT")))));

    assertThat(log)
        .contains(
            "model=\"test-model\"",
            "historyMessageCount=\"1\"",
            "modelLatencyMs",
            "outcome=\"success\"",
            "errorCode=\"none\"")
        .doesNotContain("SECRET-PROMPT", "SECRET-RESPONSE");
  }

  @Test
  void modelLogDoesNotContainPromptOrUpstreamMessage() {
    ChatModelPort failing =
        request -> {
          throw new IllegalStateException("Bearer <model-secret>");
        };

    String log =
        capture(
            ObservedChatModelPort.class,
            () ->
                new ObservedChatModelPort(failing, "test-model")
                    .generate(
                        new ChatModelRequest(
                            List.of(new ChatMessage(MessageRole.USER, "TOP-SECRET-MODEL")))));

    assertThat(log)
        .contains("modelLatencyMs", "historyMessageCount")
        .doesNotContain("TOP-SECRET-MODEL", "model-secret", "Bearer");
  }

  @Test
  void databaseLogDoesNotContainSessionOrUpstreamMessage() {
    SessionRepository failing =
        new SessionRepository() {
          @Override
          public SessionSnapshot load(String sessionId) {
            throw new IllegalStateException("Bearer <database-secret>");
          }

          @Override
          public void appendTurn(String sessionId, PersistedTurn turn) {
            throw new IllegalStateException("Bearer <database-secret>");
          }
        };

    String log =
        capture(
            ObservedSessionRepository.class,
            () -> new ObservedSessionRepository(failing).load("private-database-session"));

    assertThat(log)
        .contains("databaseLatencyMs")
        .doesNotContain("private-database-session", "database-secret", "Bearer");
  }

  @Test
  void databaseSuccessLogsLoadAndAppendWithoutSensitiveData() {
    var turn =
        new PersistedTurn(
            new ChatMessage(MessageRole.USER, "SECRET-QUESTION"),
            OffsetDateTime.parse("2026-07-13T08:00:00+08:00"),
            new ChatMessage(MessageRole.ASSISTANT, "SECRET-ANSWER"),
            OffsetDateTime.parse("2026-07-13T08:00:01+08:00"));
    SessionRepository successful =
        new SessionRepository() {
          @Override
          public SessionSnapshot load(String sessionId) {
            return new SessionSnapshot(sessionId, List.of(), 0);
          }

          @Override
          public void appendTurn(String sessionId, PersistedTurn persistedTurn) {}
        };
    var observed = new ObservedSessionRepository(successful);

    String log =
        captureSuccess(
            ObservedSessionRepository.class,
            () -> {
              observed.load("private-session");
              observed.appendTurn("private-session", turn);
            });

    assertThat(log)
        .contains("databaseLatencyMs", "outcome=\"success\"", "errorCode=\"none\"")
        .doesNotContain("private-session", "SECRET-QUESTION", "SECRET-ANSWER", "2026-07-13T08:00");
  }

  @Test
  void delegatesPendingAnchorCancellationInsteadOfFallingBackToThePortDefault() {
    PendingTurnAnchor anchor =
        PendingTurnAnchor.pending(
            "AAAAAAAAAAAAAAAAAAAAAA", "private-session", 0, "memory-forget-projection-v1");
    var calls = new int[1];
    SessionRepository delegate =
        new SessionRepository() {
          @Override
          public SessionSnapshot load(String sessionId) {
            return new SessionSnapshot(sessionId, List.of(), 0);
          }

          @Override
          public void appendTurn(String sessionId, PersistedTurn turn) {}

          @Override
          public boolean cancelPendingTurnAnchorIfMatches(PendingTurnAnchor value) {
            calls[0]++;
            return anchor.equals(value);
          }
        };

    assertThat(new ObservedSessionRepository(delegate).cancelPendingTurnAnchorIfMatches(anchor))
        .isTrue();
    assertThat(calls[0]).isOne();
  }

  private static String capture(Class<?> loggerType, Runnable action) {
    Logger logger = (Logger) LoggerFactory.getLogger(loggerType);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertThatThrownBy(action::run).isInstanceOf(IllegalStateException.class);
      return appender.list.stream()
          .map(event -> event.getFormattedMessage() + " " + event.getKeyValuePairs())
          .reduce("", String::concat);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  private static String captureSuccess(Class<?> loggerType, Runnable action) {
    Logger logger = (Logger) LoggerFactory.getLogger(loggerType);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      action.run();
      return appender.list.stream()
          .map(event -> event.getFormattedMessage() + " " + event.getKeyValuePairs())
          .reduce("", String::concat);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
