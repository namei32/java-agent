package io.namei.agent.adapter.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ModelInvocationException;
import io.namei.agent.kernel.error.ModelTimeoutException;
import io.namei.agent.kernel.model.AssistantToolCallMessage;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.ProviderReasoning;
import io.namei.agent.kernel.model.ToolResultMessage;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResultStatus;
import io.namei.agent.kernel.tool.ToolRisk;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SpringAiChatModelAdapterTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void mapsAllProjectRolesAndReturnsTrimmedAssistantText() {
    var chatModel =
        new StubChatModel(
            prompt ->
                new ChatResponse(List.of(new Generation(new AssistantMessage("\u2003回答\u2003")))));

    var result =
        new SpringAiChatModelAdapter(chatModel)
            .generate(
                new ChatModelRequest(
                    List.of(
                        new ChatMessage(MessageRole.SYSTEM, "系统"),
                        new ChatMessage(MessageRole.USER, "问题"),
                        new ChatMessage(MessageRole.ASSISTANT, "历史回答"))));

    assertThat(result.content()).isEqualTo("回答");
    assertThat(chatModel.lastPrompt().getInstructions())
        .extracting(message -> message.getMessageType())
        .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT);
    assertThat(chatModel.lastPrompt().getInstructions())
        .extracting(message -> message.getText())
        .containsExactly("系统", "问题", "历史回答");
  }

  @Test
  void appliesTrustedProviderOptionsOnlyToTextRequests() {
    var configured =
        OpenAiChatOptions.builder()
            .model("provider-model")
            .temperature(0.25)
            .customHeaders(Map.of("X-Existing", "preserved"))
            .build();
    var chatModel =
        new StubChatModel(
            ignored -> new ChatResponse(List.of(new Generation(new AssistantMessage("完成")))),
            configured);
    var adapter =
        new SpringAiChatModelAdapter(
            chatModel,
            16_384,
            java.time.Duration.ofSeconds(1),
            null,
            TrustedProviderOptions.parse("DEEPSEEK", "ENABLED", "HIGH"));

    adapter.generate(request());

    assertThat(chatModel.lastPrompt().getOptions())
        .isInstanceOfSatisfying(
            OpenAiChatOptions.class,
            options -> {
              assertThat(options.getModel()).isEqualTo("provider-model");
              assertThat(options.getTemperature()).isEqualTo(0.25);
              assertThat(options.getCustomHeaders()).containsEntry("X-Existing", "preserved");
              assertThat(options.getReasoningEffort()).isEqualTo("high");
              assertThat(options.getExtraBody())
                  .isEqualTo(Map.of("thinking", Map.of("type", "enabled")));
            });
  }

  @Test
  void toolSchemaSuppressesTrustedProviderOptionsWhilePreservingModelHeadersAndCallbacks() {
    var configured =
        OpenAiChatOptions.builder()
            .model("provider-model")
            .temperature(0.25)
            .customHeaders(Map.of("X-Existing", "preserved"))
            .reasoningEffort("unsafe")
            .extraBody(Map.of("unsafe", true))
            .build();
    var chatModel =
        new StubChatModel(
            ignored -> new ChatResponse(List.of(new Generation(new AssistantMessage("完成")))),
            configured);
    var adapter =
        new SpringAiChatModelAdapter(
            chatModel,
            16_384,
            java.time.Duration.ofSeconds(1),
            null,
            TrustedProviderOptions.parse("DASHSCOPE", "ENABLED", "NONE"));
    var definition =
        new ToolDefinition(
            "lookup", "查询", Map.of("type", "object", "properties", Map.of()), ToolRisk.READ_ONLY);

    adapter.generate(
        new ChatModelRequest(
            List.of(new ChatMessage(MessageRole.USER, "问题")), List.of(definition)));

    assertThat(chatModel.lastPrompt().getOptions())
        .isInstanceOfSatisfying(
            OpenAiChatOptions.class,
            options -> {
              assertThat(options.getModel()).isEqualTo("provider-model");
              assertThat(options.getTemperature()).isEqualTo(0.25);
              assertThat(options.getCustomHeaders()).containsEntry("X-Existing", "preserved");
              assertThat(options.getReasoningEffort()).isNull();
              assertThat(options.getExtraBody()).isEmpty();
              assertThat(options.getToolCallbacks()).singleElement();
            });
  }

  @Test
  void projectsOnlyStandardPromptAndCacheReadUsageWithoutTouchingNativeUsage() {
    Usage usage =
        new Usage() {
          @Override
          public Integer getPromptTokens() {
            return 10;
          }

          @Override
          public Integer getCompletionTokens() {
            return 2;
          }

          @Override
          public Object getNativeUsage() {
            throw new AssertionError("native usage 必须留在 Provider 边界");
          }

          @Override
          public Long getCacheReadInputTokens() {
            return 4L;
          }
        };
    var response =
        new ChatResponse(
            List.of(new Generation(new AssistantMessage("回答"))),
            ChatResponseMetadata.builder().usage(usage).build());
    var chatModel = new StubChatModel(ignored -> response);

    var result = new SpringAiChatModelAdapter(chatModel).generate(request());

    assertThat(result.cacheUsage())
        .contains(new io.namei.agent.kernel.model.ProviderCacheUsage(10, 4));
  }

  @Test
  void omitsMissingOrImpossibleUsageInsteadOfTurningItIntoAnAdapterFailure() {
    Usage impossibleUsage =
        new Usage() {
          @Override
          public Integer getPromptTokens() {
            return 3;
          }

          @Override
          public Integer getCompletionTokens() {
            return null;
          }

          @Override
          public Object getNativeUsage() {
            throw new AssertionError("native usage 必须留在 Provider 边界");
          }

          @Override
          public Long getCacheReadInputTokens() {
            return 4L;
          }
        };
    var response =
        new ChatResponse(
            List.of(new Generation(new AssistantMessage("回答"))),
            ChatResponseMetadata.builder().usage(impossibleUsage).build());

    var result =
        new SpringAiChatModelAdapter(new StubChatModel(ignored -> response)).generate(request());

    assertThat(result.cacheUsage()).isEmpty();
  }

  @Test
  void mapsToolDefinitionsAndEphemeralTranscriptWithoutExecutingCallbacks() {
    var chatModel =
        new StubChatModel(
            prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("完成")))));
    var call = new ToolCall("call-1", "lookup", Map.of("city", "上海"));
    var definition =
        new ToolDefinition(
            "lookup",
            "查询",
            Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))),
            ToolRisk.READ_ONLY);

    new SpringAiChatModelAdapter(chatModel)
        .generate(
            new ChatModelRequest(
                List.of(
                    new ChatMessage(MessageRole.USER, "问题"),
                    new AssistantToolCallMessage("查询中", List.of(call)),
                    new ToolResultMessage(call.id(), call.name(), ToolResultStatus.SUCCESS, "晴")),
                List.of(definition)));

    assertThat(chatModel.lastPrompt().getInstructions().get(1))
        .isInstanceOfSatisfying(
            AssistantMessage.class,
            message -> {
              assertThat(message.getToolCalls()).hasSize(1);
              assertThat(message.getToolCalls().getFirst().arguments())
                  .isEqualTo("{\"city\":\"上海\"}");
            });
    assertThat(chatModel.lastPrompt().getInstructions().get(2))
        .isInstanceOfSatisfying(
            ToolResponseMessage.class,
            message -> {
              assertThat(message.getResponses()).hasSize(1);
              assertThat(message.getResponses().getFirst().id()).isEqualTo("call-1");
              assertThat(message.getResponses().getFirst().responseData()).isEqualTo("晴");
            });
    assertThat(chatModel.lastPrompt().getOptions())
        .isInstanceOfSatisfying(
            ToolCallingChatOptions.class,
            options -> {
              assertThat(options.getToolCallbacks()).hasSize(1);
              var callback = options.getToolCallbacks().getFirst();
              assertThat(callback.getToolDefinition().name()).isEqualTo("lookup");
              assertThat(callback.getToolDefinition().inputSchema()).contains("\"city\"");
              assertThatThrownBy(() -> callback.call("{}"))
                  .isInstanceOf(UnsupportedOperationException.class);
            });
  }

  @Test
  void parsesProviderToolCallsIntoProjectProtocol() {
    var output =
        AssistantMessage.builder()
            .content("")
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall(
                        "call-1", "function", "lookup", "{\"city\":\"上海\"}")))
            .build();
    var chatModel = new StubChatModel(prompt -> new ChatResponse(List.of(new Generation(output))));

    var response = new SpringAiChatModelAdapter(chatModel).generate(request());

    assertThat(response.content()).isEmpty();
    assertThat(response.toolCalls())
        .containsExactly(new ToolCall("call-1", "lookup", Map.of("city", "上海")));
  }

  @Test
  void stripsEmbeddedReasoningAndCarriesItOnlyForSafeLocalToolContinuation() {
    var call = new ToolCall("call-1", "lookup", Map.of());
    var output =
        AssistantMessage.builder()
            .content("<think>仅供 Provider 使用</think>正在查询")
            .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "lookup", "{}")))
            .build();
    var chatModel = new StubChatModel(prompt -> new ChatResponse(List.of(new Generation(output))));
    var adapter =
        new SpringAiChatModelAdapter(
            chatModel,
            16_384,
            java.time.Duration.ofSeconds(1),
            null,
            TrustedProviderOptions.parse("DEEPSEEK", "ENABLED", "NONE", "SAFE_LOCAL"));

    var first = adapter.generate(request());

    assertThat(first.content()).isEqualTo("正在查询");
    assertThat(first.reasoning()).contains(ProviderReasoning.from("仅供 Provider 使用").orElseThrow());

    adapter.generate(
        new ChatModelRequest(
            List.of(
                new ChatMessage(MessageRole.USER, "问题"),
                new AssistantToolCallMessage(
                    first.content(), List.of(call), first.reasoning().orElseThrow()),
                new ToolResultMessage(call.id(), call.name(), ToolResultStatus.SUCCESS, "结果"))));

    assertThat(chatModel.lastPrompt().getInstructions().get(1))
        .isInstanceOfSatisfying(
            AssistantMessage.class,
            message ->
                assertThat(message.getMetadata())
                    .containsEntry("reasoningContent", "仅供 Provider 使用"));
  }

  @Test
  void stripsEmbeddedReasoningFromFinalTextWithoutRetainingIt() {
    var output = new AssistantMessage("<think>不能离开 Provider 边界</think>最终回答");
    var adapter =
        new SpringAiChatModelAdapter(
            new StubChatModel(prompt -> new ChatResponse(List.of(new Generation(output)))),
            16_384,
            java.time.Duration.ofSeconds(1),
            null,
            TrustedProviderOptions.parse("DEEPSEEK", "ENABLED", "NONE", "SAFE_LOCAL"));

    var response = adapter.generate(request());

    assertThat(response.content()).isEqualTo("最终回答");
    assertThat(response.reasoning()).isEmpty();
  }

  @Test
  void rejectsProviderArgumentsThatExceedUtf8ByteLimitBeforeParsing() {
    String arguments = "{\"city\":\"上海\"}";
    var output =
        AssistantMessage.builder()
            .content("")
            .toolCalls(
                List.of(new AssistantMessage.ToolCall("call-1", "function", "lookup", arguments)))
            .build();
    var chatModel = new StubChatModel(prompt -> new ChatResponse(List.of(new Generation(output))));

    assertThatThrownBy(
            () ->
                new SpringAiChatModelAdapter(
                        chatModel, arguments.getBytes(StandardCharsets.UTF_8).length - 1)
                    .generate(request()))
        .isInstanceOf(InvalidModelResponseException.class)
        .hasMessage("模型 Tool Call 格式无效")
        .hasMessageNotContaining(arguments);
  }

  @Test
  void acceptsProviderArgumentsAtExactUtf8ByteLimit() {
    String arguments = "{\"city\":\"上海\"}";
    var output =
        AssistantMessage.builder()
            .content("")
            .toolCalls(
                List.of(new AssistantMessage.ToolCall("call-1", "function", "lookup", arguments)))
            .build();
    var chatModel = new StubChatModel(prompt -> new ChatResponse(List.of(new Generation(output))));

    var response =
        new SpringAiChatModelAdapter(chatModel, arguments.getBytes(StandardCharsets.UTF_8).length)
            .generate(request());

    assertThat(response.toolCalls())
        .containsExactly(new ToolCall("call-1", "lookup", Map.of("city", "上海")));
  }

  @Test
  @Tag("compat")
  void executesArgumentsByteLimitGoldenAgainstProductionAdapter() throws Exception {
    JsonNode fixture =
        JSON.readTree(
            Path.of(System.getProperty("golden.root")).resolve("tools/runtime-safety.json"));
    JsonNode goldenCase = null;
    for (JsonNode candidate : fixture.path("cases")) {
      if (candidate.path("id").asString().equals("arguments-byte-limit")) {
        goldenCase = candidate;
        break;
      }
    }
    assertThat(goldenCase).isNotNull();
    JsonNode input = goldenCase.path("input");
    String arguments = input.path("rawArguments").asString();
    var output =
        AssistantMessage.builder()
            .content("")
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall("golden-call", "function", "lookup", arguments)))
            .build();
    var chatModel = new StubChatModel(prompt -> new ChatResponse(List.of(new Generation(output))));

    String outcome;
    try {
      new SpringAiChatModelAdapter(chatModel, input.path("maxArgumentBytes").asInt())
          .generate(request());
      outcome = "COMPLETED";
    } catch (InvalidModelResponseException exception) {
      outcome = "INVALID_MODEL_RESPONSE";
    }

    assertThat(outcome).isEqualTo(goldenCase.path("expected").path("outcome").asString());
  }

  @Test
  void rejectsMissingResponseParts() {
    ChatModel nullResponseModel = new StubChatModel(prompt -> null);
    ChatModel missingGenerationModel = new StubChatModel(prompt -> new ChatResponse(List.of()));
    ChatModel missingOutputModel =
        new StubChatModel(prompt -> new ChatResponse(List.of(new Generation(null))));

    assertInvalidResponse(nullResponseModel, "缺少");
    assertInvalidResponse(missingGenerationModel, "缺少");
    assertInvalidResponse(missingOutputModel, "缺少");
  }

  @Test
  void rejectsBlankAssistantText() {
    ChatModel chatModel =
        new StubChatModel(
            prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(" \t ")))));

    assertInvalidResponse(chatModel, "空响应");
  }

  @Test
  void mapsTimeoutCauseChainToStableProjectException() {
    var providerFailure = new IllegalStateException("provider", new SocketTimeoutException("slow"));
    ChatModel chatModel =
        new StubChatModel(
            prompt -> {
              throw providerFailure;
            });

    assertThatThrownBy(() -> new SpringAiChatModelAdapter(chatModel).generate(request()))
        .isInstanceOf(ModelTimeoutException.class)
        .hasMessage("模型调用超时")
        .hasCause(providerFailure);
  }

  @Test
  void mapsOtherProviderFailuresToStableProjectException() {
    var providerFailure = new IllegalStateException("provider payload must stay internal");
    ChatModel chatModel =
        new StubChatModel(
            prompt -> {
              throw providerFailure;
            });

    assertThatThrownBy(() -> new SpringAiChatModelAdapter(chatModel).generate(request()))
        .isInstanceOf(ModelInvocationException.class)
        .hasMessage("模型调用失败")
        .hasCause(providerFailure);
  }

  private static void assertInvalidResponse(ChatModel chatModel, String messageFragment) {
    assertThatThrownBy(() -> new SpringAiChatModelAdapter(chatModel).generate(request()))
        .isInstanceOf(InvalidModelResponseException.class)
        .hasMessageContaining(messageFragment);
  }

  private static ChatModelRequest request() {
    return new ChatModelRequest(List.of(new ChatMessage(MessageRole.USER, "问题")));
  }

  private static final class StubChatModel implements ChatModel {
    private final Function<Prompt, ChatResponse> response;
    private final ChatOptions options;
    private Prompt lastPrompt;

    private StubChatModel(Function<Prompt, ChatResponse> response) {
      this(response, null);
    }

    private StubChatModel(Function<Prompt, ChatResponse> response, ChatOptions options) {
      this.response = response;
      this.options = options;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      lastPrompt = prompt;
      return response.apply(prompt);
    }

    @Override
    public ChatOptions getOptions() {
      return options;
    }

    private Prompt lastPrompt() {
      return lastPrompt;
    }
  }
}
