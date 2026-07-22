package io.namei.agent.bootstrap.config;

import io.namei.agent.kernel.prompt.PromptBudget;
import io.namei.agent.kernel.prompt.PromptMode;
import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 版本化 Prompt 编译器使用的有界、显式选择加入配置。 */
@ConfigurationProperties("agent.prompt")
public final class PromptProperties {
  private final PromptMode mode;
  private final ZoneId zoneId;
  private final PromptBudget budget;

  @ConstructorBinding
  public PromptProperties(
      @DefaultValue("MINIMAL") String mode,
      @DefaultValue("UTC") String zoneId,
      @DefaultValue("100000") int maxSystemTokens,
      @DefaultValue("100000") int maxFrameTokens,
      @DefaultValue("200000") int maxTotalTokens,
      @DefaultValue("9") int maxSections) {
    this.mode = PromptMode.parse(mode);
    this.zoneId = parseZoneId(zoneId);
    this.budget = new PromptBudget(maxSystemTokens, maxFrameTokens, maxTotalTokens, maxSections);
  }

  public PromptMode mode() {
    return mode;
  }

  public ZoneId zoneId() {
    return zoneId;
  }

  public PromptBudget budget() {
    return budget;
  }

  @Override
  public String toString() {
    return "PromptProperties[mode=" + mode + ", zoneId=" + zoneId + ", budget=<configured>]";
  }

  private static ZoneId parseZoneId(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("agent.prompt.zone-id 必填");
    }
    try {
      return ZoneId.of(value);
    } catch (DateTimeException invalid) {
      throw new IllegalArgumentException("agent.prompt.zone-id 必须是有效时区", invalid);
    }
  }
}
