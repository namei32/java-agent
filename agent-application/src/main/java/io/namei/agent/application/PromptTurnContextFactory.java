package io.namei.agent.application;

import io.namei.agent.kernel.prompt.PromptTurnContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/** Creates channel-owned prompt metadata without inferring values from user content. */
public final class PromptTurnContextFactory {
  private final Clock clock;
  private final ZoneId zoneId;

  public PromptTurnContextFactory(ZoneId zoneId) {
    this(Clock.systemUTC(), zoneId);
  }

  public PromptTurnContextFactory(Clock clock, ZoneId zoneId) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
  }

  public PromptTurnContext create(Instant requestTime, String channel, String sessionId) {
    return new PromptTurnContext(requestTime, zoneId, channel, sessionId);
  }

  public PromptTurnContext create(String channel, String sessionId) {
    return create(clock.instant(), channel, sessionId);
  }
}
