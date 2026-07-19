package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.ProviderCacheUsage;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ProviderCacheUsageContractFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void consumesEveryR10P4ProviderCacheUsageCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("provider/r10-provider-cache-usage-v1.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString()).isEqualTo("provider/r10-provider-cache-usage-v1");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("cases")).hasSize(5);

    for (JsonNode testCase : fixture.path("cases")) {
      var collector = new ProviderTurnUsageCollector();
      for (JsonNode response : testCase.path("input").path("responses")) {
        collector.accept(
            new ChatModelResponse("fixture response", List.of(), cacheUsage(response)));
      }

      var expected = testCase.path("expected");
      var actual = collector.snapshot();
      assertThat(actual.modelCallCount())
          .as(testCase.path("id").asString())
          .isEqualTo(expected.path("modelCallCount").asInt());
      assertNullableLong(actual.cachePromptTokens(), expected.path("cachePromptTokens"));
      assertNullableLong(actual.cacheHitTokens(), expected.path("cacheHitTokens"));
    }
  }

  private static ProviderCacheUsage cacheUsage(JsonNode response) {
    if (response.path("promptTokens").isNull() || response.path("cacheHitTokens").isNull()) {
      return null;
    }
    return new ProviderCacheUsage(
        response.path("promptTokens").asLong(), response.path("cacheHitTokens").asLong());
  }

  private static void assertNullableLong(Long actual, JsonNode expected) {
    if (expected.isNull()) {
      assertThat(actual).isNull();
    } else {
      assertThat(actual).isEqualTo(expected.asLong());
    }
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
