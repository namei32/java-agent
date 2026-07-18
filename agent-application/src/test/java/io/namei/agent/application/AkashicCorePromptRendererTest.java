package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.prompt.PromptContractViolation;
import io.namei.agent.kernel.prompt.PromptMode;
import io.namei.agent.kernel.prompt.PromptSectionId;
import io.namei.agent.kernel.prompt.PromptStableCode;
import io.namei.agent.kernel.prompt.PromptTurnContext;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class AkashicCorePromptRendererTest {
  private final AkashicCorePromptRenderer renderer = new AkashicCorePromptRenderer("身份", "行为规则");

  @Test
  void rendersVersionedCoreSectionsWithAnInjectedTimeAndExplicitSession() {
    List<?> sections =
        renderer.render(
            PromptMode.AKASHIC_CORE,
            new PromptTurnContext(
                Instant.parse("2026-07-18T13:14:15Z"), ZoneId.of("UTC"), "cli", "cli:local"));

    assertThat(sections)
        .extracting("id", "content")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(PromptSectionId.IDENTITY, "身份"),
            org.assertj.core.groups.Tuple.tuple(PromptSectionId.BEHAVIOR_RULES, "行为规则"),
            org.assertj.core.groups.Tuple.tuple(
                PromptSectionId.SESSION_CONTEXT,
                "## Current Session\n"
                    + "Channel: cli\n"
                    + "Session ID: cli:local\n\n"
                    + "[当前消息时间: 2026-07-18 13:14:15 UTC | "
                    + "request_time=2026-07-18T13:14:15Z | "
                    + "今天=2026-07-18（周六） | 昨天=2026-07-17（周五） | "
                    + "明天=2026-07-19（周日） | 后天=2026-07-20（周一） | "
                    + "weekday=Saturday | 相对时间以此为准]"));
  }

  @Test
  void keepsMinimalModeResourceFreeAndRejectsUnsafeTurnMetadata() {
    assertThat(
            renderer.render(
                PromptMode.MINIMAL,
                new PromptTurnContext(
                    Instant.parse("2026-07-18T13:14:15Z"), ZoneId.of("UTC"), null, null)))
        .isEmpty();

    assertThatThrownBy(
            () ->
                new PromptTurnContext(
                    Instant.parse("2026-07-18T13:14:15Z"), ZoneId.of("UTC"), "CLI", "cli:local"))
        .isInstanceOf(PromptContractViolation.class)
        .extracting(error -> ((PromptContractViolation) error).code())
        .isEqualTo(PromptStableCode.PROMPT_CONTEXT_INVALID);
  }
}
