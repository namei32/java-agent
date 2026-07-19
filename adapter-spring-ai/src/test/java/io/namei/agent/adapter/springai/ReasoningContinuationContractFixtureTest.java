package io.namei.agent.adapter.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.model.ProviderReasoning;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ReasoningContinuationContractFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void consumesEveryR10P2ReasoningContinuationCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("provider/r10-reasoning-tool-continuation-v1.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString())
        .isEqualTo("provider/r10-reasoning-tool-continuation-v1");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("cases")).hasSize(6);

    for (JsonNode testCase : fixture.path("cases")) {
      JsonNode input = testCase.path("input");
      JsonNode expected = testCase.path("expected");
      if (!expected.path("configurationAccepted").asBoolean()) {
        assertThatThrownBy(
                () ->
                    TrustedProviderOptions.parse(
                        input.path("profile").asString(),
                        "ENABLED",
                        "NONE",
                        input.path("mode").asString()))
            .as(testCase.path("id").asString())
            .isInstanceOf(IllegalArgumentException.class);
        continue;
      }
      var options =
          TrustedProviderOptions.parse(
              input.path("profile").asString(), "ENABLED", "NONE", input.path("mode").asString());
      int reasoningCodePoints = input.path("reasoningCodePoints").asInt();
      var reasoning = ProviderReasoning.from("理".repeat(reasoningCodePoints));
      int replayCodePoints =
          options.allowsReasoningContinuation() && input.path("hasToolCalls").asBoolean()
              ? reasoning.map(ProviderReasoning::codePointCount).orElse(0)
              : 0;

      assertThat(options.allowsToolThinking())
          .as(testCase.path("id").asString())
          .isEqualTo(expected.path("allowToolThinking").asBoolean());
      assertThat(replayCodePoints)
          .as(testCase.path("id").asString())
          .isEqualTo(expected.path("replayCodePoints").asInt());
    }
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
