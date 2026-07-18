package io.namei.agent.kernel.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ProactivePeerAutomationContractFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void consumesEveryR14P0ProactivePeerAutomationContractCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("proactive/r14-proactive-peer-automation-v1.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString())
        .isEqualTo("proactive/r14-proactive-peer-automation-v1");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("contractEvidence").path("approvedOn").asString())
        .isEqualTo("2026-07-19");
    assertThat(fixture.path("limits").path("maxSourceTextCodePoints").asInt()).isEqualTo(4_000);
    assertThat(fixture.path("cases")).hasSize(28);

    Map<String, Long> groups =
        StreamSupport.stream(fixture.path("cases").spliterator(), false)
            .map(testCase -> testCase.path("group").asString())
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    assertThat(groups)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "scheduler-transition", 7L,
                "source", 6L,
                "decision-delivery", 3L,
                "memory-mutation", 4L,
                "peer", 8L));

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
        case "scheduler-transition" -> {
          assertThat(
                  ProactiveJobTransition.isAllowed(
                      ProactiveJobState.parse(input.path("from").asString()),
                      ProactiveJobState.parse(input.path("to").asString())))
              .as(caseId)
              .isEqualTo(expected.path("accepted").asBoolean());
          return;
        }
        case "source" -> verifySource(input, expected, caseId);
        case "decision-delivery" -> verifyDelivery(input, expected, caseId);
        case "memory-mutation" -> ProactiveMemoryMutation.parse(input.path("kind").asString());
        case "peer" -> verifyPeer(input, expected, caseId);
        default -> throw new AssertionError("未知 R14 P0 Fixture Group: " + caseId);
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

  private static void verifySource(JsonNode input, JsonNode expected, String caseId) {
    ProactiveSourceItem item =
        ProactiveSourceItem.fixedLocal(
            ProactiveSourceKind.parse(input.path("kind").asString()),
            input.path("sourceRef").asString(),
            input.path("text").asString());
    assertThat(item.safeText()).as(caseId).isEqualTo(expected.path("text").asString());
  }

  private static void verifyDelivery(JsonNode input, JsonNode expected, String caseId) {
    ProactiveDecision decision =
        "REQUESTED".equals(input.path("decision").asString())
            ? ProactiveDecision.requested()
            : ProactiveDecision.skipped(ProactiveStableCode.parse(input.path("code").asString()));
    ProactiveDeliveryBoundary boundary = ProactiveDeliveryBoundary.from(decision);
    if (expected.has("disposition")) {
      assertThat(boundary.disposition().name())
          .as(caseId)
          .isEqualTo(expected.path("disposition").asString());
    }
    assertThat(boundary.allowsExternalDelivery())
        .as(caseId)
        .isEqualTo(expected.path("externalDelivery").asBoolean());
    if (expected.has("transportAuthorized")) {
      assertThat(boundary.transportAuthorized())
          .as(caseId)
          .isEqualTo(expected.path("transportAuthorized").asBoolean());
    }
  }

  private static void verifyPeer(JsonNode input, JsonNode expected, String caseId) {
    if (input.has("trust")) {
      new PeerIdentity(
          PeerTrust.parse(input.path("trust").asString()), input.path("peerRef").asString());
      return;
    }
    if (input.has("taskRef")) {
      PeerTaskRef.parse(input.path("taskRef").asString());
      return;
    }
    PeerTaskState state = PeerTaskState.parse(input.path("state").asString());
    assertThat(state.terminal()).as(caseId).isEqualTo(expected.path("terminal").asBoolean());
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
