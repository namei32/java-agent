package io.namei.agent.adapter.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class TrustedProviderOptionsFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void consumesEveryR10P1TrustedProviderOptionsCase() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("provider/r10-provider-options-v1.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString()).isEqualTo("provider/r10-provider-options-v1");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("cases")).hasSize(7);

    for (JsonNode testCase : fixture.path("cases")) {
      JsonNode input = testCase.path("input");
      if (testCase.path("expected").path("outcome").asString().equals("REJECTED")) {
        assertThatThrownBy(() -> policy(input))
            .as(testCase.path("id").asString())
            .isInstanceOf(IllegalArgumentException.class);
        continue;
      }
      var options =
          policy(input)
              .apply(
                  OpenAiChatOptions.builder().model("preserved-model").temperature(0.25).build(),
                  input.path("hasToolSchema").asBoolean());
      JsonNode expected = testCase.path("expected");
      assertThat(options.getModel()).isEqualTo("preserved-model");
      assertThat(options.getTemperature()).isEqualTo(0.25);
      assertThat(options.getReasoningEffort())
          .isEqualTo(nullableText(expected.path("reasoningEffort")));
      assertThat(options.getExtraBody()).isEqualTo(map(expected.path("extraBody")));
    }
  }

  private static TrustedProviderOptions policy(JsonNode input) {
    return TrustedProviderOptions.parse(
        input.path("profile").asString(),
        input.path("thinkingMode").asString(),
        input.path("reasoningEffort").asString());
  }

  private static String nullableText(JsonNode value) {
    return value.isNull() ? null : value.asString();
  }

  private static Map<String, Object> map(JsonNode value) throws Exception {
    return JSON.readValue(value.toString(), Map.class);
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
