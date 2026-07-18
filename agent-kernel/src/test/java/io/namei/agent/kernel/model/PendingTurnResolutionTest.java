package io.namei.agent.kernel.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class PendingTurnResolutionTest {
  @Test
  void acceptsOnlyAVersionedAssistantProjectionAndRedactsItsContent() {
    PendingTurnResolution resolution =
        new PendingTurnResolution(
            "pending-projection-v1",
            new ChatMessage(MessageRole.ASSISTANT, "受控操作已完成。"),
            OffsetDateTime.parse("2026-07-19T09:00:00+08:00"));

    assertThat(resolution.toString()).contains("<redacted>").doesNotContain("受控操作已完成。");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new PendingTurnResolution(
                    "pending-projection-v1",
                    new ChatMessage(MessageRole.USER, "不应写入"),
                    OffsetDateTime.parse("2026-07-19T09:00:00+08:00")));
  }
}
