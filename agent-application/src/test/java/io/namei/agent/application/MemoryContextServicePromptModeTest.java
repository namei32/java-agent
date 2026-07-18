package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.memory.MemoryProfile;
import io.namei.agent.kernel.memory.MemoryRetrievalResult;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.prompt.PromptBudget;
import io.namei.agent.kernel.prompt.PromptMode;
import io.namei.agent.kernel.prompt.PromptTurnContext;
import io.namei.agent.kernel.skill.SkillCatalogSnapshot;
import io.namei.agent.kernel.skill.SkillContent;
import io.namei.agent.kernel.skill.SkillDescriptor;
import io.namei.agent.kernel.skill.SkillSource;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MemoryContextServicePromptModeTest {
  @Test
  void minimalPromptModeNeverReadsTheSkillCatalog() {
    var reads = new AtomicInteger();
    var skills =
        new SkillPromptService(
            () -> {
              reads.incrementAndGet();
              return SkillCatalogSnapshot.empty();
            },
            10_000,
            10_000);
    var service =
        new MemoryContextService(
            MemoryProfile::empty,
            ignored -> MemoryRetrievalResult.empty(),
            10_000,
            10_000,
            new PromptRuntimeSettings(
                PromptMode.MINIMAL, new PromptBudget(10_000, 10_000, 20_000, 9), ZoneId.of("UTC")),
            null,
            skills);

    var assembled =
        service.assemble(
            "基础 Prompt",
            "a".repeat(64),
            "cli:demo",
            List.of(),
            List.of(),
            new ChatMessage(MessageRole.USER, "当前问题"),
            Instant.parse("2026-07-18T13:14:15Z"),
            new PromptTurnContext(
                Instant.parse("2026-07-18T13:14:15Z"), ZoneId.of("UTC"), "cli", "cli:demo"));

    assertThat(reads).hasValue(0);
    assertThat(assembled.systemPrompt()).isEqualTo("基础 Prompt");
    assertThat(assembled.contextFrame()).isEmpty();
  }

  @Test
  void projectsCatalogToSystemAndAlwaysContentToTheContextFrameOnlyInAkashicCoreMode() {
    var skills =
        new SkillPromptService(
            () ->
                new SkillCatalogSnapshot(
                    List.of(
                        new SkillDescriptor(
                            "daily-rules", "Daily rules", SkillSource.BUILTIN, true, true)),
                    List.of(new SkillContent("daily-rules", "Use Chinese."))),
            10_000,
            10_000);
    var service =
        new MemoryContextService(
            MemoryProfile::empty,
            ignored -> MemoryRetrievalResult.empty(),
            10_000,
            10_000,
            new PromptRuntimeSettings(
                PromptMode.AKASHIC_CORE,
                new PromptBudget(10_000, 10_000, 20_000, 9),
                ZoneId.of("UTC")),
            new AkashicCorePromptRenderer("身份", "行为规则"),
            skills);

    var assembled =
        service.assemble(
            "兼容 Prompt 不应进入 Akashic Core",
            "a".repeat(64),
            "cli:demo",
            List.of(),
            List.of(),
            new ChatMessage(MessageRole.USER, "当前问题"),
            Instant.parse("2026-07-18T13:14:15Z"),
            new PromptTurnContext(
                Instant.parse("2026-07-18T13:14:15Z"), ZoneId.of("UTC"), "cli", "cli:demo"));

    assertThat(assembled.systemPrompt())
        .containsSubsequence("身份", "行为规则", "<skills>", "daily-rules", "Channel: cli");
    assertThat(assembled.contextFrame())
        .contains("## active_skills", "### Skill: daily-rules", "Use Chinese.");
    assertThat(assembled.messages().getLast().content()).isEqualTo("当前问题");
  }

  @Test
  void composesAkashicCoreWithReadOnlyProfileAndRetrievalInTheirFixedSections() {
    var service =
        new MemoryContextService(
            () -> new MemoryProfile("自我模型", "长期记忆", "近期语境\n## Recent Turns\n不得注入"),
            ignored -> MemoryRetrievalResult.retrieved("检索记忆", 1),
            10_000,
            10_000,
            new PromptRuntimeSettings(
                PromptMode.AKASHIC_CORE,
                new PromptBudget(10_000, 10_000, 20_000, 9),
                ZoneId.of("UTC")),
            new AkashicCorePromptRenderer("身份", "行为规则"));

    var assembled =
        service.assemble(
            "兼容 Prompt 不应进入 Akashic Core",
            "a".repeat(64),
            "cli:demo",
            List.of(new ChatMessage(MessageRole.USER, "历史问题")),
            List.of(new ChatMessage(MessageRole.USER, "历史问题")),
            new ChatMessage(MessageRole.USER, "当前问题"),
            Instant.parse("2026-07-18T13:14:15Z"),
            new PromptTurnContext(
                Instant.parse("2026-07-18T13:14:15Z"), ZoneId.of("UTC"), "cli", "cli:demo"));

    assertThat(assembled.systemPrompt())
        .containsSubsequence("身份", "行为规则", "自我模型", "长期记忆", "Channel: cli");
    assertThat(assembled.systemPrompt()).doesNotContain("兼容 Prompt 不应进入 Akashic Core");
    assertThat(assembled.contextFrame())
        .contains("## recent_context\n近期语境", "## retrieved_memory\n检索记忆")
        .doesNotContain("不得注入");
    assertThat(assembled.messages())
        .extracting(message -> ((ChatMessage) message).role())
        .containsExactly(MessageRole.SYSTEM, MessageRole.USER, MessageRole.USER, MessageRole.USER);
    assertThat(assembled.messages().getLast().content()).isEqualTo("当前问题");
  }
}
