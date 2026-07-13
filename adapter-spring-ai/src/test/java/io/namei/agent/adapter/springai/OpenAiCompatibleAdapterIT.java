package io.namei.agent.adapter.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ModelInvocationException;
import io.namei.agent.kernel.error.ModelTimeoutException;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.port.ChatModelPort;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = OpenAiCompatibleAdapterIT.TestApplication.class)
class OpenAiCompatibleAdapterIT {
  private static final OpenAiStubServer SERVER = createServer();

  @Autowired ChatModelPort model;

  @AfterAll
  static void stopServer() {
    SERVER.close();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.ai.openai.base-url", SERVER::baseUrl);
    registry.add("spring.ai.openai.api-key", () -> "test-key");
    registry.add("spring.ai.openai.chat.model", () -> "test-model");
    registry.add("spring.ai.openai.chat.timeout", () -> "50ms");
    registry.add("spring.ai.openai.chat.max-retries", () -> "0");
    registry.add("spring.ai.model.embedding", () -> "none");
  }

  @Test
  void callsLocalOpenAiCompatibleEndpoint() {
    SERVER.respond(200, OpenAiStubServer.successBody("回答"));

    assertThat(model.generate(request()).content()).isEqualTo("回答");
  }

  @ParameterizedTest
  @MethodSource("upstreamFailures")
  void mapsUpstreamStatusToStableException(int status) {
    SERVER.respond(status, "{\"error\":{\"message\":\"failed\"}}");

    assertThatThrownBy(() -> model.generate(request()))
        .isInstanceOf(ModelInvocationException.class)
        .hasMessage("模型调用失败");
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
    SERVER.respondAfter(Duration.ofMillis(250), OpenAiStubServer.successBody("迟到"));

    assertThatThrownBy(() -> model.generate(request())).isInstanceOf(ModelTimeoutException.class);
  }

  private static Stream<Integer> upstreamFailures() {
    return Stream.of(401, 429, 500);
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
