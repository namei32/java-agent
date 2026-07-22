package io.namei.agent.application;

import io.namei.agent.kernel.prompt.PromptContractViolation;
import io.namei.agent.kernel.prompt.PromptMode;
import io.namei.agent.kernel.prompt.PromptSection;
import io.namei.agent.kernel.prompt.PromptSectionId;
import io.namei.agent.kernel.prompt.PromptStableCode;
import io.namei.agent.kernel.prompt.PromptTurnContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 仅渲染 Java 自持、基于 Classpath 的 Akashic Core Prompt Section。 */
public final class AkashicCorePromptRenderer {
  private static final String IDENTITY_RESOURCE = "prompts/akashic-core-identity.md";
  private static final String BEHAVIOR_RESOURCE = "prompts/akashic-core-behavior.md";
  private static final DateTimeFormatter DISPLAY_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT);

  private final String identity;
  private final String behaviorRules;

  public AkashicCorePromptRenderer(String identity, String behaviorRules) {
    this.identity = requireText(identity);
    this.behaviorRules = requireText(behaviorRules);
  }

  public static AkashicCorePromptRenderer fromClasspath() {
    return new AkashicCorePromptRenderer(
        readResource(IDENTITY_RESOURCE), readResource(BEHAVIOR_RESOURCE));
  }

  public List<PromptSection> render(PromptMode mode, PromptTurnContext turnContext) {
    Objects.requireNonNull(mode, "mode");
    PromptTurnContext context = Objects.requireNonNull(turnContext, "turnContext");
    if (mode == PromptMode.MINIMAL) {
      return List.of();
    }
    return List.of(
        section(PromptSectionId.IDENTITY, identity),
        section(PromptSectionId.BEHAVIOR_RULES, behaviorRules),
        section(PromptSectionId.SESSION_CONTEXT, renderSessionContext(context)));
  }

  private static PromptSection section(PromptSectionId id, String content) {
    return new PromptSection(id, id.placement(), content);
  }

  private static String renderSessionContext(PromptTurnContext context) {
    ZonedDateTime requestTime = context.requestTime().atZone(context.zoneId());
    return "## Current Session\n"
        + "Channel: "
        + context.channel()
        + "\nSession ID: "
        + context.sessionId()
        + "\n\n"
        + "[当前消息时间: "
        + DISPLAY_TIME.format(requestTime)
        + " | request_time="
        + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(requestTime)
        + " | 今天="
        + dateWithWeekday(requestTime)
        + " | 昨天="
        + dateWithWeekday(requestTime.minusDays(1))
        + " | 明天="
        + dateWithWeekday(requestTime.plusDays(1))
        + " | 后天="
        + dateWithWeekday(requestTime.plusDays(2))
        + " | weekday="
        + requestTime.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        + " | 相对时间以此为准]";
  }

  private static String dateWithWeekday(ZonedDateTime time) {
    return time.toLocalDate() + "（" + weekdayChinese(time) + "）";
  }

  private static String weekdayChinese(ZonedDateTime time) {
    return switch (time.getDayOfWeek()) {
      case MONDAY -> "周一";
      case TUESDAY -> "周二";
      case WEDNESDAY -> "周三";
      case THURSDAY -> "周四";
      case FRIDAY -> "周五";
      case SATURDAY -> "周六";
      case SUNDAY -> "周日";
    };
  }

  private static String readResource(String name) {
    try (InputStream input =
        AkashicCorePromptRenderer.class.getClassLoader().getResourceAsStream(name)) {
      if (input == null) {
        throw unavailable();
      }
      return requireText(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException invalid) {
      throw unavailable();
    }
  }

  private static String requireText(String value) {
    if (value == null || value.strip().isEmpty()) {
      throw unavailable();
    }
    return value.strip();
  }

  private static PromptContractViolation unavailable() {
    return new PromptContractViolation(PromptStableCode.PROMPT_SECTION_UNAVAILABLE);
  }
}
