package io.namei.agent.adapter.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.concurrent.CancellationSignal;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolRisk;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = OpenAiTrustedProviderOptionsIT.TestApplication.class)
class OpenAiTrustedProviderOptionsIT {
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
    registry.add("agent.model.provider-options.profile", () -> "DEEPSEEK");
    registry.add("agent.model.provider-options.thinking-mode", () -> "ENABLED");
    registry.add("agent.model.provider-options.reasoning-effort", () -> "HIGH");
  }

  @Test
  void sendsOnlyTheFixedAllowlistedOptionsForATextRequest() throws Exception {
    SERVER.respond(200, OpenAiStubServer.successBody("回答"));

    assertThat(model.generate(textRequest()).content()).isEqualTo("回答");

    var body = JSON.readTree(SERVER.requestBodies().getFirst());
    assertThat(body.path("reasoning_effort").asString()).isEqualTo("high");
    assertThat(body.path("thinking").path("type").asString()).isEqualTo("enabled");
    assertThat(body.has("extra_body")).isFalse();
  }

  @Test
  void suppressesThinkingAndEffortWhenAToolSchemaIsPresent() throws Exception {
    SERVER.respond(200, OpenAiStubServer.successBody("回答"));
    var definition =
        new ToolDefinition(
            "lookup", "查询", Map.of("type", "object", "properties", Map.of()), ToolRisk.READ_ONLY);

    assertThat(
            model
                .generate(
                    new ChatModelRequest(
                        List.of(new ChatMessage(MessageRole.USER, "问题")), List.of(definition)))
                .content())
        .isEqualTo("回答");

    var body = JSON.readTree(SERVER.requestBodies().getFirst());
    assertThat(body.has("reasoning_effort")).isFalse();
    assertThat(body.has("thinking")).isFalse();
    assertThat(body.path("tools").get(0).path("function").path("name").asString())
        .isEqualTo("lookup");
  }

  @Test
  void usesTheSameFixedAllowlistedOptionsForAStreamingTextRequest() throws Exception {
    SERVER.respondSse(List.of(OpenAiStubServer.textDelta("回"), OpenAiStubServer.finished("stop")));

    assertThat(model.generate(textRequest(), ignored -> {}, CancellationSignal.none()).content())
        .isEqualTo("回");

    var body = JSON.readTree(SERVER.requestBodies().getFirst());
    assertThat(body.path("stream").asBoolean()).isTrue();
    assertThat(body.path("reasoning_effort").asString()).isEqualTo("high");
    assertThat(body.path("thinking").path("type").asString()).isEqualTo("enabled");
  }

  private static ChatModelRequest textRequest() {
    return new ChatModelRequest(List.of(new ChatMessage(MessageRole.USER, "问题")));
  }

  private static OpenAiStubServer createServer() {
    try {
      return new OpenAiStubServer();
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(SpringAiAdapterConfiguration.class)
  static class TestApplication {}
}
