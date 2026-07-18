package io.namei.agent.kernel.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ProactiveContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void consumesEveryVersionedProactiveContractCase() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("proactive/proactive-runtime-v1.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(ProactiveContract.CURRENT_VERSION);
    assertThat(fixture.path("suite").asString()).isEqualTo("proactive/proactive-runtime");
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
        case "schedule" -> schedule(input);
        case "job" -> job(input);
        case "decision" -> decision(input);
        case "subagent" ->
            new SubagentBudget(
                input.path("maxPromptCharacters").asInt(),
                input.path("maxResultCharacters").asInt(),
                input.path("maxSteps").asInt(),
                Duration.ofMillis(input.path("timeoutMillis").asLong()));
        case "stable-code" ->
            assertThat(ProactiveStableCode.parse(input.path("code").asString()).retryable())
                .as(caseId)
                .isEqualTo(expected.path("retryable").asBoolean());
        default -> throw new AssertionError("未知 Proactive Fixture Group: " + caseId);
      }
      if (!expected.path("accepted").asBoolean()) {
        fail("Case 应被拒绝: " + caseId);
      }
    } catch (ProactiveContractViolation violation) {
      if (expected.path("accepted").asBoolean()) {
        throw new AssertionError("Case 应被接受: " + caseId, violation);
      }
      assertThat(violation.code().name()).as(caseId).isEqualTo(expected.path("code").asString());
    }
  }

  private static ProactiveSchedule schedule(JsonNode input) {
    return new ProactiveSchedule(
        ProactiveScheduleKind.parse(input.path("kind").asString()),
        Instant.parse(input.path("nextRunAt").asString()),
        input.hasNonNull("everyMillis")
            ? Duration.ofMillis(input.path("everyMillis").asLong())
            : null);
  }

  private static ScheduledJob job(JsonNode input) {
    return new ScheduledJob(
        ProactiveJobRef.parse(input.path("jobRef").asString()),
        schedule(input.path("schedule")),
        input.path("targetHash").asString(),
        input.path("idempotencyKey").asString(),
        ProactiveJobState.parse(input.path("state").asString()),
        input.path("attempts").asInt(),
        input.path("maxAttempts").asInt());
  }

  private static ProactiveDecision decision(JsonNode input) {
    return "REQUESTED".equals(input.path("kind").asString())
        ? ProactiveDecision.requested()
        : ProactiveDecision.skipped(ProactiveStableCode.parse(input.path("code").asString()));
  }

  private static Path goldenRoot() {
    String configured = System.getProperty("golden.root");
    assertThat(configured).as("Maven 必须提供 golden.root").isNotBlank();
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
