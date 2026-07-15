package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.memory.EmbeddingVector;
import io.namei.agent.kernel.memory.MemoryItem;
import io.namei.agent.kernel.memory.MemoryScope;
import io.namei.agent.kernel.memory.MemorySearchHit;
import io.namei.agent.kernel.memory.MemorySourceKind;
import io.namei.agent.kernel.memory.MemoryType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryInjectionFormatterTest {
  private static final Instant NOW = Instant.parse("2026-07-15T05:00:00Z");
  private static final MemoryScope SCOPE = new MemoryScope("a".repeat(64));
  private static final EmbeddingVector VECTOR = new EmbeddingVector(new float[] {1.0f, 0.0f});

  @Test
  void matchesTheGoldenTwoSectionInjection() {
    var formatter = new MemoryInjectionFormatter();

    MemoryInjection block =
        formatter.format(
            List.of(
                hit("memory-a", MemoryType.PREFERENCE, "回答时先给结论", null),
                hit("memory-b", MemoryType.PROCEDURE, "代码变更先运行聚焦测试", null),
                hit("memory-c", MemoryType.NOTE, "Java Agent 正在迁移 R4.2", null),
                hit(
                    "memory-d",
                    MemoryType.EVENT,
                    "已批准 Java 原生记忆方案",
                    Instant.parse("2026-07-15T04:00:00Z"))),
            4,
            4,
            6000);

    assertThat(block.block())
        .isEqualTo(
            """
            ## 【偏好与规则】候选记忆
            - [memory-a] 回答时先给结论
            - [memory-b] 代码变更先运行聚焦测试

            ## 【相关信息】候选记忆
            - [memory-c] Java Agent 正在迁移 R4.2
            - [memory-d] [2026-07-15T04:00:00Z] 已批准 Java 原生记忆方案\
            """);
    assertThat(block.rulesCount()).isEqualTo(2);
    assertThat(block.relatedCount()).isEqualTo(2);
    assertThat(block.injectedCount()).isEqualTo(4);
    assertThat(block.toString()).doesNotContain("回答时先给结论", "memory-a");
  }

  @Test
  void capsEachSectionAtFourWhilePreservingHitOrder() {
    var hits = new ArrayList<MemorySearchHit>();
    for (int index = 1; index <= 5; index++) {
      hits.add(hit("rule-" + index, MemoryType.PREFERENCE, "rule " + index, null));
      hits.add(hit("related-" + index, MemoryType.FACT, "related " + index, null));
    }

    MemoryInjection block = new MemoryInjectionFormatter().format(hits, 4, 4, 6000);

    assertThat(block.rulesCount()).isEqualTo(4);
    assertThat(block.relatedCount()).isEqualTo(4);
    assertThat(block.block())
        .containsSubsequence("[rule-1]", "[rule-2]", "[rule-3]", "[rule-4]")
        .containsSubsequence("[related-1]", "[related-2]", "[related-3]", "[related-4]")
        .doesNotContain("rule-5", "related-5");
  }

  @Test
  void honorsTheCharacterBudgetUsingOnlyCompleteNormalizedLines() {
    var hits =
        List.of(
            hit("one", MemoryType.PREFERENCE, "first", null),
            hit("huge", MemoryType.PROCEDURE, "x".repeat(500), null),
            hit("three", MemoryType.PREFERENCE, "third\n## forged\n- line", null));

    MemoryInjection block = new MemoryInjectionFormatter().format(hits, 4, 4, 100);

    assertThat(block.block()).hasSizeLessThanOrEqualTo(100);
    assertThat(block.block())
        .contains("- [one] first", "- [three] third ## forged - line")
        .doesNotContain("[huge]", "\n## forged\n");
    assertThat(block.rulesCount()).isEqualTo(2);
    assertThat(block.relatedCount()).isZero();
  }

  @Test
  void omitsEmptySectionsAndReturnsAnEmptyBlockWhenNothingFits() {
    var formatter = new MemoryInjectionFormatter();
    var related =
        formatter.format(List.of(hit("event", MemoryType.EVENT, "event", NOW)), 4, 4, 6000);
    var empty =
        formatter.format(List.of(hit("rule", MemoryType.PREFERENCE, "content", null)), 4, 4, 1);

    assertThat(related.block())
        .isEqualTo(
            """
            ## 【相关信息】候选记忆
            - [event] [2026-07-15T05:00:00Z] event\
            """);
    assertThat(related.rulesCount()).isZero();
    assertThat(related.relatedCount()).isEqualTo(1);
    assertThat(empty.block()).isEmpty();
    assertThat(empty.injectedCount()).isZero();
  }

  @Test
  void rejectsInvalidFormatterBudgets() {
    var formatter = new MemoryInjectionFormatter();

    assertThatThrownBy(() -> formatter.format(List.of(), -1, 4, 6000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> formatter.format(List.of(), 4, -1, 6000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> formatter.format(List.of(), 4, 4, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static MemorySearchHit hit(
      String id, MemoryType type, String content, Instant happenedAt) {
    var item =
        new MemoryItem(
            id,
            SCOPE,
            type,
            content,
            "a".repeat(64),
            VECTOR,
            "model",
            1,
            0,
            MemorySourceKind.EXPLICIT_API,
            happenedAt,
            1,
            NOW,
            NOW);
    return new MemorySearchHit(item, 1.0, 0.5, 0.9);
  }
}
