package io.namei.agent.kernel.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PromptContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void consumesEveryVersionedPromptContractCase() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("prompt/prompt-orchestration-v1.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(PromptContract.CURRENT_VERSION);
    assertThat(fixture.path("suite").asString()).isEqualTo("prompt/prompt-orchestration");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("cases")).hasSize(16);

    for (JsonNode testCase : fixture.path("cases")) {
      verify(testCase);
    }
  }

  private static void verify(JsonNode testCase) {
    String caseId = testCase.path("id").asString();
    JsonNode input = testCase.path("input");
    JsonNode expected = testCase.path("expected");
    try {
      switch (testCase.path("group").asString()) {
        case "section-id" -> {
          PromptSectionId id = PromptSectionId.parse(input.path("id").asString());
          assertThat(id.placement().name())
              .as(caseId)
              .isEqualTo(expected.path("placement").asString());
          assertThat(id.priority()).as(caseId).isEqualTo(expected.path("priority").asInt());
          assertThat(id.trimAllowed())
              .as(caseId)
              .isEqualTo(expected.path("trimAllowed").asBoolean());
        }
        case "mode" -> PromptMode.parse(input.path("mode").asString());
        case "section" ->
            new PromptSection(
                PromptSectionId.parse(input.path("id").asString()),
                PromptPlacement.parse(input.path("placement").asString()),
                input.path("content").asString());
        case "budget" ->
            new PromptBudget(
                input.path("maxSystemTokens").asInt(),
                input.path("maxFrameTokens").asInt(),
                input.path("maxTotalTokens").asInt(),
                input.path("maxSections").asInt());
        default -> throw new AssertionError("未知 Prompt Fixture Group: " + caseId);
      }
      if (!expected.path("accepted").asBoolean()) {
        fail("Case 应被拒绝: " + caseId);
      }
    } catch (PromptContractViolation violation) {
      if (expected.path("accepted").asBoolean()) {
        throw new AssertionError("Case 应被接受: " + caseId, violation);
      }
      assertThat(violation.code().name()).as(caseId).isEqualTo(expected.path("code").asString());
    }
  }

  private static Path goldenRoot() {
    String configured = System.getProperty("golden.root");
    assertThat(configured).as("Maven 必须提供 golden.root").isNotBlank();
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
