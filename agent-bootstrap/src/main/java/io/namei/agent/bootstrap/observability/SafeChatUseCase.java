package io.namei.agent.bootstrap.observability;

import io.namei.agent.application.ChatCommand;
import io.namei.agent.application.ChatResult;
import io.namei.agent.application.ChatUseCase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SafeChatUseCase implements ChatUseCase {
  private static final Logger logger = LoggerFactory.getLogger(SafeChatUseCase.class);

  private final ChatUseCase delegate;
  private final Clock clock;

  public SafeChatUseCase(ChatUseCase delegate, Clock clock) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public ChatResult chat(ChatCommand command) {
    Instant started = clock.instant();
    String sessionHash = hash(command.sessionId());
    try {
      ChatResult result = delegate.chat(command);
      log("success", "none", sessionHash, started);
      return result;
    } catch (RuntimeException exception) {
      log("failure", exception.getClass().getSimpleName(), sessionHash, started);
      throw exception;
    }
  }

  private void log(String outcome, String errorCode, String sessionHash, Instant started) {
    logger
        .atInfo()
        .addKeyValue("sessionIdHash", sessionHash)
        .addKeyValue("totalLatencyMs", Duration.between(started, clock.instant()).toMillis())
        .addKeyValue("outcome", outcome)
        .addKeyValue("errorCode", errorCode)
        .log("chat request completed");
  }

  private static String hash(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 12);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 不可用", exception);
    }
  }
}
