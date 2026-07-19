package io.namei.agent.adapter.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.concurrent.CancellationSignal;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.MessageRole;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ProviderFailureContractFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void consumesEveryR10ProviderFailureContractCase() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("provider/r10-provider-failure-v1.json"));
    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString()).isEqualTo("provider/r10-provider-failure-v1");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("cases")).hasSize(9);

    for (JsonNode testCase : fixture.path("cases")) {
      verify(testCase, fixture.path("defaults").path("sensitiveFragment").asString());
    }
  }

  private static void verify(JsonNode testCase, String sensitiveFragment) {
    JsonNode input = testCase.path("input");
    RuntimeException providerFailure =
        providerFailure(input.path("failure").asString(), input.path("nested").asBoolean());
    RuntimeException actual;
    if ("SYNC".equals(input.path("transport").asString())) {
      actual =
          capture(
              () ->
                  new SpringAiChatModelAdapter(new SyncFailureChatModel(ignored -> providerFailure))
                      .generate(request()));
    } else {
      actual =
          capture(
              () ->
                  new SpringAiChatModelAdapter(
                          new StreamFailureChatModel(ignored -> Flux.error(providerFailure)),
                          16_384,
                          Duration.ofSeconds(1))
                      .generate(request(), ignored -> {}, CancellationSignal.none()));
    }

    assertThat(actual.getClass().getSimpleName())
        .as(testCase.path("id").asString())
        .isEqualTo(testCase.path("expected").path("exception").asString());
    assertThat(actual.getMessage()).doesNotContain(sensitiveFragment);
  }

  private static RuntimeException providerFailure(String scenario, boolean nested) {
    RuntimeException failure =
        switch (scenario) {
          case "SAFETY" -> new IllegalStateException("content_policy_violation provider-secret");
          case "CONTEXT" -> new IllegalStateException("context_length_exceeded provider-secret");
          case "TIMEOUT_WITH_SAFETY_MARKER" ->
              new IllegalStateException(
                  "content_policy_violation provider-secret", new SocketTimeoutException("slow"));
          case "UNKNOWN" -> new IllegalStateException("provider-secret");
          default -> throw new AssertionError("未知 Provider Fixture 场景");
        };
    return nested ? new IllegalStateException("wrapper provider-secret", failure) : failure;
  }

  private static RuntimeException capture(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException failure) {
      return failure;
    }
    throw new AssertionError("预期 Provider 调用失败");
  }

  private static ChatModelRequest request() {
    return new ChatModelRequest(List.of(new ChatMessage(MessageRole.USER, "问题")));
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }

  private static final class SyncFailureChatModel implements ChatModel {
    private final Function<Prompt, RuntimeException> failure;

    private SyncFailureChatModel(Function<Prompt, RuntimeException> failure) {
      this.failure = failure;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      throw failure.apply(prompt);
    }
  }

  private static final class StreamFailureChatModel implements ChatModel {
    private final Function<Prompt, Flux<ChatResponse>> stream;

    private StreamFailureChatModel(Function<Prompt, Flux<ChatResponse>> stream) {
      this.stream = stream;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      throw new AssertionError("流式测试不应调用同步入口");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      return stream.apply(prompt);
    }
  }
}
