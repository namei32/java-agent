package io.namei.agent.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.namei.agent.application.ChatCommand;
import io.namei.agent.application.ChatUseCase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SafeChatUseCaseTest {
  @Test
  void neverLogsMessageOrUpstreamSecret() {
    Logger logger = (Logger) LoggerFactory.getLogger(SafeChatUseCase.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      ChatUseCase failing =
          command -> {
            throw new IllegalStateException("Bearer <secret-key>");
          };
      var observed =
          new SafeChatUseCase(
              failing, Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC));

      assertThatThrownBy(
              () -> observed.chat(new ChatCommand("private-session", "TOP-SECRET-CONTENT")))
          .isInstanceOf(IllegalStateException.class);

      String rendered =
          appender.list.stream()
              .map(event -> event.getFormattedMessage() + " " + event.getKeyValuePairs())
              .reduce("", String::concat);
      assertThat(rendered)
          .contains("sessionIdHash", "outcome=\"failure\"")
          .doesNotContain("private-session", "TOP-SECRET-CONTENT", "secret-key", "Bearer");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
