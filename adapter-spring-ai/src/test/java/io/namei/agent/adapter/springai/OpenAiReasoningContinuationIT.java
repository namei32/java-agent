package io.namei.agent.adapter.springai;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Verifies the Spring AI metadata mapping with a local OpenAI-compatible HTTP endpoint. */
@SpringBootTest(classes = OpenAiReasoningContinuationIT.TestApplication.class)
class OpenAiReasoningContinuationIT {
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
    registry.add("agent.model.provider-options.reasoning-effort", () -> "NONE");
    registry.add("agent.model.reasoning-continuation.mode", () -> "SAFE_LOCAL");
  }

  @Test
  void carriesReasoningContentOnlyAcrossTheImmediateToolContinuation() throws Exception {
    var definition =
        new ToolDefinition(
            "lookup",
            "查询固定结果",
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
            ToolRisk.READ_ONLY);
    SERVER.respond(
        200,
        OpenAiStubServer.toolCallBodyWithReasoning(
            "call-1", "lookup", "{}", "Provider 私有 reasoning"));

    var first =
        model.generate(
            new ChatModelRequest(
                List.of(new ChatMessage(MessageRole.USER, "查询")), List.of(definition)));

    assertThat(first.content()).isEmpty();
    assertThat(first.toolCalls()).containsExactly(new ToolCall("call-1", "lookup", Map.of()));
    assertThat(first.reasoning())
        .hasValueSatisfying(
            reasoning -> assertThat(reasoning.content()).isEqualTo("Provider 私有 reasoning"));

    SERVER.respond(200, OpenAiStubServer.successBody("最终回答"));
    ToolCall call = first.toolCalls().getFirst();
    var second =
        model.generate(
            new ChatModelRequest(
                List.of(
                    new ChatMessage(MessageRole.USER, "查询"),
                    new AssistantToolCallMessage(
                        first.content(), List.of(call), first.reasoning().orElseThrow()),
                    new ToolResultMessage(call, ToolResult.success("结果"))),
                List.of(definition)));

    assertThat(second.content()).isEqualTo("最终回答");
    assertThat(SERVER.requestBodies()).hasSize(2);
    var firstRequest = JSON.readTree(SERVER.requestBodies().getFirst());
    assertThat(firstRequest.path("thinking").path("type").asString()).isEqualTo("enabled");
    assertThat(firstRequest.path("messages").toString()).doesNotContain("Provider 私有 reasoning");
    var secondRequest = JSON.readTree(SERVER.requestBodies().get(1));
    assertThat(secondRequest.path("messages").get(1).path("reasoning_content").asString())
        .isEqualTo("Provider 私有 reasoning");
    assertThat(secondRequest.path("messages").get(2).path("role").asString()).isEqualTo("tool");
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
