package io.namei.agent.application;

import io.namei.agent.kernel.prompt.PromptTurnContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/** 创建 Channel 自持的 Prompt 元数据，不从用户内容推断值。 */
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
