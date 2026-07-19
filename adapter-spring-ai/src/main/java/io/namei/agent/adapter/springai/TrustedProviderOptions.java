package io.namei.agent.adapter.springai;

import java.util.Locale;
import java.util.Map;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Strict Java-owned, text-only Provider options. It never accepts a free-form provider body. */
final class TrustedProviderOptions {
  private final Profile profile;
  private final ThinkingMode thinkingMode;
  private final ReasoningEffort reasoningEffort;

  private TrustedProviderOptions(
      Profile profile, ThinkingMode thinkingMode, ReasoningEffort reasoningEffort) {
    this.profile = profile;
    this.thinkingMode = thinkingMode;
    this.reasoningEffort = reasoningEffort;
  }

  static TrustedProviderOptions disabled() {
    return new TrustedProviderOptions(
        Profile.DISABLED, ThinkingMode.DISABLED, ReasoningEffort.NONE);
  }

  static TrustedProviderOptions parse(
      String configuredProfile, String configuredThinkingMode, String configuredReasoningEffort) {
    var profile = Profile.parse(configuredProfile, "agent.model.provider-options.profile");
    var thinkingMode =
        ThinkingMode.parse(configuredThinkingMode, "agent.model.provider-options.thinking-mode");
    var reasoningEffort =
        ReasoningEffort.parse(
            configuredReasoningEffort, "agent.model.provider-options.reasoning-effort");
    if (profile == Profile.DISABLED) {
      if (thinkingMode != ThinkingMode.DISABLED || reasoningEffort != ReasoningEffort.NONE) {
        throw new IllegalArgumentException("禁用 Provider Options 时不能设置 thinking 或 reasoning effort");
      }
      return disabled();
    }
    if (profile == Profile.DASHSCOPE) {
      if (thinkingMode != ThinkingMode.ENABLED || reasoningEffort != ReasoningEffort.NONE) {
        throw new IllegalArgumentException("DASHSCOPE 只允许启用固定 thinking body，且不允许 reasoning effort");
      }
    } else if (thinkingMode == ThinkingMode.DISABLED && reasoningEffort == ReasoningEffort.NONE) {
      throw new IllegalArgumentException("DEEPSEEK 必须显式启用 thinking 或 reasoning effort");
    }
    return new TrustedProviderOptions(profile, thinkingMode, reasoningEffort);
  }

  OpenAiChatOptions apply(OpenAiChatOptions configured, boolean hasToolSchema) {
    if (profile == Profile.DISABLED) {
      return configured;
    }
    var builder = configured.mutate();
    if (hasToolSchema) {
      return builder.reasoningEffort(null).extraBody(Map.of()).build();
    }
    if (reasoningEffort != ReasoningEffort.NONE) {
      builder.reasoningEffort(reasoningEffort.wireValue());
    }
    if (thinkingMode == ThinkingMode.ENABLED) {
      builder.extraBody(
          switch (profile) {
            case DEEPSEEK -> Map.of("thinking", Map.of("type", "enabled"));
            case DASHSCOPE -> Map.of("enable_thinking", true);
            case DISABLED -> Map.of();
          });
    }
    return builder.build();
  }

  boolean isEnabled() {
    return profile != Profile.DISABLED;
  }

  private enum Profile {
    DISABLED,
    DEEPSEEK,
    DASHSCOPE;

    private static Profile parse(String value, String field) {
      return parseEnum(Profile.class, value, field);
    }
  }

  private enum ThinkingMode {
    DISABLED,
    ENABLED;

    private static ThinkingMode parse(String value, String field) {
      return parseEnum(ThinkingMode.class, value, field);
    }
  }

  private enum ReasoningEffort {
    NONE(null),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    private final String wireValue;

    ReasoningEffort(String wireValue) {
      this.wireValue = wireValue;
    }

    private String wireValue() {
      return wireValue;
    }

    private static ReasoningEffort parse(String value, String field) {
      return parseEnum(ReasoningEffort.class, value, field);
    }
  }

  private static <T extends Enum<T>> T parseEnum(Class<T> type, String configured, String field) {
    if (configured == null || !configured.equals(configured.strip()) || configured.isBlank()) {
      throw new IllegalArgumentException(field + " 必须是严格大写枚举");
    }
    if (!configured.equals(configured.toUpperCase(Locale.ROOT))) {
      throw new IllegalArgumentException(field + " 必须是严格大写枚举");
    }
    try {
      return Enum.valueOf(type, configured);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(field + " 包含未知值", exception);
    }
  }
}
