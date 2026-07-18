package io.namei.agent.kernel.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class PendingTurnAnchorGoldenTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void executesEveryVersionedSessionAnchorFixtureCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("tools/pending-operation-v1.json").toFile());
    assertThat(fixture.path("cases").size()).isEqualTo(54);
    for (JsonNode testCase : fixture.path("cases")) {
      String id = testCase.path("id").asText();
      if (id.startsWith("anchor-")
          && !id.startsWith("anchor-store-")
          && !id.startsWith("anchor-recovery-")
          && !id.startsWith("anchor-rehearsal-")) {
        verify(id);
      }
    }
  }

  private static void verify(String id) {
    PendingTurnAnchor pending = pending();
    switch (id) {
      case "anchor-accepts-exact-bindings" ->
          assertThat(pending.state()).isEqualTo(PendingTurnAnchorState.PENDING_APPROVAL);
      case "anchor-rejects-unknown-version" ->
          assertThatIllegalArgumentException()
              .isThrownBy(
                  () ->
                      new PendingTurnAnchor(
                          2,
                          "AAAAAAAAAAAAAAAAAAAAAA",
                          "session-1",
                          4,
                          6,
                          PendingTurnAnchorState.PENDING_APPROVAL,
                          "pending-projection-v1"));
      case "anchor-rejects-nonopaque-operation-reference" ->
          assertThatIllegalArgumentException()
              .isThrownBy(
                  () ->
                      PendingTurnAnchor.pending(
                          "not-an-operation-reference", "session-1", 4, "pending-projection-v1"));
      case "anchor-requires-following-resume-sequence" ->
          assertThatIllegalArgumentException()
              .isThrownBy(
                  () ->
                      new PendingTurnAnchor(
                          1,
                          "AAAAAAAAAAAAAAAAAAAAAA",
                          "session-1",
                          4,
                          5,
                          PendingTurnAnchorState.PENDING_APPROVAL,
                          "pending-projection-v1"));
      case "anchor-rejects-unresolvable-cursor" ->
          assertThatIllegalArgumentException()
              .isThrownBy(
                  () ->
                      PendingTurnAnchor.pending(
                          "AAAAAAAAAAAAAAAAAAAAAA",
                          "session-1",
                          Long.MAX_VALUE - 2,
                          "pending-projection-v1"));
      case "anchor-cancelled-is-terminal" -> {
        PendingTurnAnchor cancelled = pending.transitionTo(PendingTurnAnchorState.CANCELLED);
        assertThat(cancelled.isTerminal()).isTrue();
        assertThatThrownBy(() -> cancelled.transitionTo(PendingTurnAnchorState.COMMITTED))
            .isInstanceOf(IllegalStateException.class);
      }
      case "anchor-stale-is-terminal" ->
          assertThat(pending.transitionTo(PendingTurnAnchorState.STALE_SESSION).isTerminal())
              .isTrue();
      case "anchor-to-string-redacts-bindings" ->
          assertThat(pending.toString())
              .doesNotContain("AAAAAAAAAAAAAAAAAAAAAA")
              .doesNotContain("session-1")
              .contains("<redacted>");
      default -> throw new AssertionError("未知 Session Anchor Fixture Case: " + id);
    }
  }

  private static PendingTurnAnchor pending() {
    return PendingTurnAnchor.pending(
        "AAAAAAAAAAAAAAAAAAAAAA", "session-1", 4, "pending-projection-v1");
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
