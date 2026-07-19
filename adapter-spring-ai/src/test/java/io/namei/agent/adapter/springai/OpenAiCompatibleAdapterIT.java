package io.namei.agent.adapter.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ModelContextLimitException;
import io.namei.agent.kernel.error.ModelInvocationException;
import io.namei.agent.kernel.error.ModelSafetyRejectedException;
import io.namei.agent.kernel.error.ModelTimeoutException;
import io.namei.agent.kernel.model.AssistantToolCallMessage;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.ToolResultMessage;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = OpenAiCompatibleAdapterIT.TestApplication.class)
class OpenAiCompatibleAdapterIT {
  private static final OpenAiStubServer SERVER = createServer();
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired ChatModelPort model;

  @BeforeEach
  void resetServer() {
    SERVER.reset();
  }

  @AfterAll
  static void stopServer() {
    SERVER.close();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.ai.openai.base-url", SERVER::baseUrl);
    registry.add("spring.ai.openai.api-key", () -> "test-key");
    registry.add("spring.ai.openai.chat.model", () -> "test-model");
    registry.add("spring.ai.openai.chat.timeout", () -> "500ms");
    registry.add("spring.ai.openai.chat.max-retries", () -> "0");
    registry.add("spring.ai.model.embedding", () -> "none");
  }

  @Test
  void callsLocalOpenAiCompatibleEndpoint() throws Exception {
    SERVER.respond(200, OpenAiStubServer.successBody("回答"));

    assertThat(model.generate(request()).content()).isEqualTo("回答");
    var request = JSON.readTree(SERVER.requestBodies().getFirst());
    assertThat(request.has("reasoning_effort")).isFalse();
    assertThat(request.has("thinking")).isFalse();
  }

  @Test
  @Tag("failure")
  void exchangesToolSchemaCallAndResultThroughRealOpenAiChatModel() throws Exception {
    var definition =
        new ToolDefinition(
            "current_time",
            "返回当前 UTC 时间",
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
            ToolRisk.READ_ONLY);
    SERVER.respond(200, OpenAiStubServer.toolCallBody("call-1", "current_time", "{}"));

    var first =
        model.generate(
            new ChatModelRequest(
                List.of(new ChatMessage(MessageRole.USER, "现在几点？")), List.of(definition)));

    assertThat(first.toolCalls()).containsExactly(new ToolCall("call-1", "current_time", Map.of()));
    var call = first.toolCalls().getFirst();
    SERVER.respond(200, OpenAiStubServer.successBody("现在是固定时间"));

    var second =
        model.generate(
            new ChatModelRequest(
                List.of(
                    new ChatMessage(MessageRole.USER, "现在几点？"),
                    new AssistantToolCallMessage("", List.of(call)),
                    new ToolResultMessage(call, ToolResult.success("2026-07-14T04:00:00Z"))),
                List.of(definition)));

    assertThat(second.content()).isEqualTo("现在是固定时间");
    assertThat(SERVER.requestBodies()).hasSize(2);
    var firstRequest = JSON.readTree(SERVER.requestBodies().getFirst());
    assertThat(firstRequest.path("model").asString()).isEqualTo("test-model");
    assertThat(firstRequest.path("tools").get(0).path("function").path("name").asString())
        .isEqualTo("current_time");
    assertThat(
            firstRequest
                .path("tools")
                .get(0)
                .path("function")
                .path("parameters")
                .path("type")
                .asString())
        .isEqualTo("object");

    var secondRequest = JSON.readTree(SERVER.requestBodies().get(1));
    assertThat(
            secondRequest.path("messages").get(1).path("tool_calls").get(0).path("id").asString())
        .isEqualTo("call-1");
    assertThat(secondRequest.path("messages").get(2).path("role").asString()).isEqualTo("tool");
    assertThat(secondRequest.path("messages").get(2).path("tool_call_id").asString())
        .isEqualTo("call-1");
  }

  @ParameterizedTest
  @MethodSource("upstreamFailures")
  void mapsUpstreamStatusToStableException(int status) {
    SERVER.respond(status, "{\"error\":{\"message\":\"failed\"}}");

    assertThatThrownBy(() -> model.generate(request()))
        .isInstanceOf(ModelInvocationException.class)
        .hasMessage("模型调用失败");
  }

  @ParameterizedTest
  @MethodSource("classifiedProviderFailureBodies")
  @Tag("failure")
  void mapsRealCompatibleProviderFailureBodyWithoutLeakingIt(
      String marker, Class<? extends RuntimeException> expected) {
    SERVER.respond(400, "{\"error\":{\"message\":\"" + marker + " provider-secret\"}}");

    assertThatThrownBy(() -> model.generate(request()))
        .isInstanceOf(expected)
        .hasMessageNotContaining(marker)
        .hasMessageNotContaining("provider-secret");
  }

  @Test
  void rejectsInvalidOrEmptyResponse() {
    SERVER.respond(200, "not-json");
    assertThatThrownBy(() -> model.generate(request()))
        .isInstanceOf(ModelInvocationException.class);

    SERVER.respond(200, "{\"choices\":[]}");
    assertThatThrownBy(() -> model.generate(request()))
        .isInstanceOf(InvalidModelResponseException.class);
  }

  @Test
  void mapsTimeout() {
    SERVER.respondAfter(Duration.ofSeconds(2), OpenAiStubServer.successBody("迟到"));

    assertThatThrownBy(() -> model.generate(request())).isInstanceOf(ModelTimeoutException.class);
  }

  private static Stream<Integer> upstreamFailures() {
    return Stream.of(401, 429, 500);
  }

  private static Stream<Arguments> classifiedProviderFailureBodies() {
    return Stream.of(
        Arguments.of("content_policy_violation", ModelSafetyRejectedException.class),
        Arguments.of("context_length_exceeded", ModelContextLimitException.class));
  }

  private static OpenAiStubServer createServer() {
    try {
      return new OpenAiStubServer();
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static ChatModelRequest request() {
    return new ChatModelRequest(List.of(new ChatMessage(MessageRole.USER, "问题")));
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(SpringAiAdapterConfiguration.class)
  static class TestApplication {}
}
