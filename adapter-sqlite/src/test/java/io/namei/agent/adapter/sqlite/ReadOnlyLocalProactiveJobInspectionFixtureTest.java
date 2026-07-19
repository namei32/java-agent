package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.proactive.ProactiveJobRef;
import io.namei.agent.kernel.proactive.ProactiveJobState;
import io.namei.agent.kernel.proactive.ProactiveSchedule;
import io.namei.agent.kernel.proactive.ProactiveScheduleKind;
import io.namei.agent.kernel.proactive.ScheduledJob;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ReadOnlyLocalProactiveJobInspectionFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Instant DUE = Instant.parse("2026-07-20T00:00:00Z");

  @TempDir Path temporaryDirectory;

  @Test
  void consumesTheActiveOrderingAndTerminalExclusionFixtureCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(
            goldenRoot().resolve("proactive/read-only-local-proactive-job-inspection-v1.json"));
    JsonNode testCase =
        java.util.stream.StreamSupport.stream(fixture.path("cases").spliterator(), false)
            .filter(candidate -> "store".equals(candidate.path("group").asString()))
            .findFirst()
            .orElseThrow();

    var schema =
        new ProactiveSchemaInitializer(
            temporaryDirectory.resolve("proactive/proactive-runtime.db"), 5_000);
    schema.initialize();
    var store = new JdbcProactiveJobStore(schema);
    store.schedule(job("finished-summary", DUE, "a".repeat(64)));
    var claimed = store.claimNext(DUE, "worker-a", Duration.ofSeconds(30)).orElseThrow();
    var running = store.markRunning(claimed, DUE.plusSeconds(1)).orElseThrow();
    assertThat(store.complete(running, ProactiveJobState.SUCCEEDED, DUE.plusSeconds(2))).isTrue();
    Instant next = DUE.plus(Duration.ofHours(1));
    store.schedule(job("zeta-summary", next, "b".repeat(64)));
    store.schedule(job("alpha-summary", next, "c".repeat(64)));

    assertThat(store.listActive(testCase.path("input").path("limit").asInt()))
        .extracting(snapshot -> snapshot.jobRef().value())
        .containsExactlyElementsOf(
            java.util.stream.StreamSupport.stream(
                    testCase.path("expected").path("jobRefs").spliterator(), false)
                .map(JsonNode::asString)
                .toList());
  }

  private static ScheduledJob job(String jobRef, Instant nextRunAt, String idempotencyKey) {
    return new ScheduledJob(
        ProactiveJobRef.parse(jobRef),
        new ProactiveSchedule(ProactiveScheduleKind.AT, nextRunAt, null),
        "d".repeat(64),
        idempotencyKey,
        ProactiveJobState.SCHEDULED,
        0,
        3);
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
