package io.namei.agent.kernel.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.channel.OutboundMessageType;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ControlEventProjectionTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String RAW_TURN = "raw-turn-secret";
  private static final String RAW_SESSION = "telegram:raw-session-secret";
  private static final MessageRoute RAW_ROUTE =
      new MessageRoute("telegram", "raw-conversation-secret");

  @Test
  void projectsEveryKernelOwnedSseFixtureCase() throws Exception {
    JsonNode fixture = fixture();
    ControlTurnRef reference =
        ControlTurnRef.parse(fixture.path("defaults").path("turnRef").asString());
    int evaluated = 0;

    for (JsonNode testCase : fixture.path("cases")) {
      if (!testCase.path("component").asString().equals("event-projection")) {
        continue;
      }
      evaluated++;
      JsonNode input = testCase.path("input");
      JsonNode expected = testCase.path("expected");
      ControlEventProjection actual = ControlEventProjection.from(reference, outbound(input));

      if (expected.has("sequence")) {
        assertThat(actual.sequence())
            .as(testCase.path("id").asString())
            .isEqualTo(expected.path("sequence").asLong());
        assertThat(actual.type().name()).isEqualTo(expected.path("type").asString());
        assertThat(actual.content()).isEqualTo(expected.path("content").asString());
        assertThat(actual.code()).isEqualTo(expected.path("code").asString());
        assertThat(actual.retryable()).isEqualTo(expected.path("retryable").asBoolean());
      }
      String json = JSON.writeValueAsString(actual);
      assertThat(json)
          .doesNotContain(RAW_TURN, RAW_SESSION, "raw-conversation-secret", "route", "sessionId");
    }

    assertThat(evaluated).isEqualTo(5);
  }

  @Test
  void rejectsCallerForgedFailureRetryability() {
    ControlTurnRef reference = ControlTurnRef.parse("AAECAwQFBgcICQoLDA0ODw");

    assertThatThrownBy(
            () ->
                new ControlEventProjection(
                    ControlPlaneContract.CURRENT_VERSION,
                    reference,
                    1,
                    OutboundMessageType.TURN_FAILED,
                    "",
                    "MODEL_TIMEOUT",
                    false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static OutboundMessage outbound(JsonNode input) {
    return new OutboundMessage(
        1,
        RAW_TURN,
        RAW_SESSION,
        RAW_ROUTE,
        input.path("sequence").asLong(),
        OutboundMessageType.valueOf(input.path("type").asString()),
        input.path("content").asString(),
        input.path("code").asString(),
        input.path("retryable").asBoolean());
  }

  private static JsonNode fixture() throws Exception {
    return JSON.readTree(goldenRoot().resolve("control-plane/loopback-control-plane-v1.json"));
  }

  private static Path goldenRoot() {
    String configured = System.getProperty("golden.root");
    assertThat(configured).as("Maven 必须提供 golden.root").isNotBlank();
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
