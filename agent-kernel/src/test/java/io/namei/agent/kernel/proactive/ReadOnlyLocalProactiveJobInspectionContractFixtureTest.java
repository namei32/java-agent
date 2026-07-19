package io.namei.agent.kernel.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ReadOnlyLocalProactiveJobInspectionContractFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void consumesEveryKernelSnapshotCaseAndExcludesSchedulerSecrets() throws Exception {
    JsonNode fixture =
        JSON.readTree(
            goldenRoot().resolve("proactive/read-only-local-proactive-job-inspection-v1.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString())
        .isEqualTo("proactive/read-only-local-proactive-job-inspection-v1");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("cases")).hasSize(13);
    assertThat(fixture.path("contractEvidence").path("adr").asString())
        .isEqualTo("docs/adr/0034-expose-only-active-hash-safe-proactive-job-inspection.md");
    assertThat(List.of(ProactiveJobInspectionSnapshot.class.getRecordComponents()))
        .extracting(RecordComponent::getName)
        .containsExactly("jobRef", "schedule", "state", "attempts", "maxAttempts")
        .doesNotContain("targetHash", "idempotencyKey", "ownerId", "revision");

    for (JsonNode testCase : fixture.path("cases")) {
      if ("snapshot".equals(testCase.path("group").asString())) {
        verifySnapshot(testCase);
      }
    }
  }

  private static void verifySnapshot(JsonNode testCase) {
    JsonNode input = testCase.path("input");
    JsonNode expected = testCase.path("expected");
    try {
      var snapshot =
          new ProactiveJobInspectionSnapshot(
              ProactiveJobRef.parse(input.path("jobRef").asString()),
              schedule(input),
              ProactiveJobState.parse(input.path("state").asString()),
              input.path("attempts").asInt(),
              input.path("maxAttempts").asInt());
      if (!expected.path("accepted").asBoolean()) {
        fail("Case 应被拒绝: " + testCase.path("id").asString());
      }
      assertThat(snapshot.state().terminal()).isFalse();
      assertThat(snapshot.schedule().kind().name()).isEqualTo(input.path("schedule").asString());
    } catch (ProactiveContractViolation violation) {
      if (expected.path("accepted").asBoolean()) {
        throw new AssertionError("Case 应被接受: " + testCase.path("id").asString(), violation);
      }
      assertThat(violation.code().name()).isEqualTo(expected.path("code").asString());
    }
  }

  private static ProactiveSchedule schedule(JsonNode input) {
    Duration every =
        input.has("everySeconds") ? Duration.ofSeconds(input.path("everySeconds").asLong()) : null;
    return new ProactiveSchedule(
        ProactiveScheduleKind.valueOf(input.path("schedule").asString()),
        Instant.parse(input.path("nextRunAt").asString()),
        every);
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
