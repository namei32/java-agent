package io.namei.agent.kernel.prompt;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.regex.Pattern;

/** Channel 为单次 Prompt 组装提供的不可变、已校验元数据。 */
public record PromptTurnContext(
    Instant requestTime, ZoneId zoneId, String channel, String sessionId) {
  private static final String UNKNOWN = "unknown";
  private static final Pattern CHANNEL = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
  private static final Pattern SESSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9:_-]{0,127}");

  public PromptTurnContext {
    requestTime = Objects.requireNonNull(requestTime, "requestTime");
    zoneId = Objects.requireNonNull(zoneId, "zoneId");
    channel = normalized(channel, CHANNEL);
    sessionId = normalized(sessionId, SESSION);
  }

  private static String normalized(String value, Pattern allowed) {
    if (value == null || value.isBlank()) {
      return UNKNOWN;
    }
    if (!allowed.matcher(value).matches()) {
      throw PromptContract.violation(PromptStableCode.PROMPT_CONTEXT_INVALID);
    }
    return value;
  }
}
