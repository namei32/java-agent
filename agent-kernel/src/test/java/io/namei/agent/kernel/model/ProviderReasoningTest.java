package io.namei.agent.kernel.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderReasoningTest {
  @Test
  void acceptsExactlyTheBoundedCodePointLimitWithoutTruncation() {
    String content = "理".repeat(ProviderReasoning.MAX_CODE_POINTS);

    assertThat(ProviderReasoning.from(content))
        .hasValueSatisfying(
            reasoning -> {
              assertThat(reasoning.content()).isEqualTo(content);
              assertThat(reasoning.codePointCount()).isEqualTo(ProviderReasoning.MAX_CODE_POINTS);
            });
  }

  @Test
  void dropsBlankAndOversizedReasoningRatherThanTruncatingIt() {
    assertThat(ProviderReasoning.from(" \t ")).isEmpty();
    assertThat(ProviderReasoning.from("理".repeat(ProviderReasoning.MAX_CODE_POINTS + 1))).isEmpty();
  }
}
