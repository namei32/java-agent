package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ContextLimitRecoveryContractFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void consumesEveryR10P3ContextLimitRecoveryCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("provider/r10-context-limit-recovery-v1.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString())
        .isEqualTo("provider/r10-context-limit-recovery-v1");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("cases")).hasSize(4);

    for (JsonNode testCase : fixture.path("cases")) {
      JsonNode input = testCase.path("input");
      var policy =
          new ContextLimitRecoveryPolicy(
              ContextLimitRecoveryMode.parse(input.path("mode").asString()));

      assertThat(
              policy.plans(input.path("historySize").asInt()).stream()
                  .map(plan -> plan.trimPlan().name() + ":" + plan.historySize())
                  .toList())
          .as(testCase.path("id").asString())
          .containsExactlyElementsOf(
              StreamSupport.stream(testCase.path("expected").path("plans").spliterator(), false)
                  .map(
                      plan ->
                          plan.path("trimPlan").asString() + ":" + plan.path("historySize").asInt())
                  .toList());
    }
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
