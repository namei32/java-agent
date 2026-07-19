package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.error.ModelContextLimitException;
import io.namei.agent.kernel.error.ModelInvocationException;
import io.namei.agent.kernel.error.ModelSafetyRejectedException;
import io.namei.agent.kernel.error.ModelTimeoutException;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ProviderFailureChannelContractFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void projectsEveryR10ProviderFailureToStableChannelCode() throws Exception {
    JsonNode fixture =
        JSON.readTree(
            Path.of(System.getProperty("golden.root"))
                .resolve("provider/r10-provider-failure-v1.json"));

    for (JsonNode testCase : fixture.path("cases")) {
      JsonNode expected = testCase.path("expected");
      var code = TurnFailureClassifier.classify(exception(expected.path("exception").asString()));
      OutboundMessage outbound =
          OutboundMessage.failed("turn-1", "cli:demo", new MessageRoute("cli", "demo"), 1, code);

      assertThat(outbound.code())
          .as(testCase.path("id").asString())
          .isEqualTo(expected.path("channelCode").asString());
      assertThat(outbound.retryable())
          .as(testCase.path("id").asString())
          .isEqualTo(expected.path("retryable").asBoolean());
      assertThat(outbound.content()).isEmpty();
    }
  }

  private static RuntimeException exception(String simpleName) {
    return switch (simpleName) {
      case "ModelSafetyRejectedException" ->
          new ModelSafetyRejectedException(new IllegalStateException("provider-secret"));
      case "ModelContextLimitException" ->
          new ModelContextLimitException(new IllegalStateException("provider-secret"));
      case "ModelTimeoutException" ->
          new ModelTimeoutException("模型调用超时", new IllegalStateException("provider-secret"));
      case "ModelInvocationException" ->
          new ModelInvocationException("模型调用失败", new IllegalStateException("provider-secret"));
      default -> throw new AssertionError("未知 Provider 异常类型: " + simpleName);
    };
  }
}
