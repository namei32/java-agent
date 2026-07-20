package io.namei.agent.kernel.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class LocalFakePeerProcessContractFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void consumesEveryR14P4LocalFakePeerProcessCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("proactive/r14-local-fake-peer-process-v1.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString())
        .isEqualTo("proactive/r14-local-fake-peer-process-v1");
    assertThat(fixture.path("cases")).hasSize(15);
    for (JsonNode testCase : fixture.path("cases")) {
      verify(testCase);
    }
  }

  private static void verify(JsonNode testCase) {
    String caseId = testCase.path("id").asString();
    JsonNode input = testCase.path("input");
    JsonNode expected = testCase.path("expected");
    try {
      LocalFakePeerResult result =
          switch (testCase.path("group").asString()) {
            case "manifest" -> {
              new LocalFakePeerManifest(
                  new PeerIdentity(PeerTrust.LOCAL_FAKE, input.path("peerRef").asString()),
                  input.path("protocol").asString(),
                  input.path("version").asInt(),
                  new LocalFakePeerResourceBudget(
                      input.path("maxOutputCodePoints").asInt(),
                      Duration.ofMillis(input.path("timeoutMillis").asLong()),
                      input.path("maxConcurrentTasks").asInt()));
              yield null;
            }
            case "card" -> {
              new LocalFakePeerCard(
                  LocalFakePeerManifest.approved(),
                  LocalFakePeerTaskKind.parse(input.path("taskKind").asString()));
              yield null;
            }
            case "result", "projection" ->
                LocalFakePeerResult.terminal(
                    PeerTaskState.parse(input.path("state").asString()), output(input));
            default -> throw new AssertionError("未知 R14 P4 Fixture Group: " + caseId);
          };
      if (!expected.path("accepted").asBoolean()) {
        fail("Case 应被拒绝: " + caseId);
      }
      if (expected.has("safeOutput")) {
        assertThat(result.safeOutput())
            .as(caseId)
            .isEqualTo(expected.path("safeOutput").asString());
      }
      if (expected.has("leaksOutput")) {
        assertThat(result.toString()).as(caseId).doesNotContain(output(input));
      }
    } catch (ProactiveContractViolation violation) {
      if (expected.path("accepted").asBoolean()) {
        throw new AssertionError("Case 应被接受: " + caseId, violation);
      }
      assertThat(violation.code().name()).as(caseId).isEqualTo(expected.path("code").asString());
    }
  }

  private static String output(JsonNode input) {
    if (input.has("output")) {
      return input.path("output").asString();
    }
    return "x".repeat(input.path("outputCodePoints").asInt());
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
