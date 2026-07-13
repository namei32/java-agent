package io.namei.agent.adapter.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ModelInvocationException;
import io.namei.agent.kernel.error.ModelTimeoutException;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.AssistantToolCallMessage;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.ToolResultMessage;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResultStatus;
import io.namei.agent.kernel.tool.ToolRisk;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

class SpringAiChatModelAdapterTest {
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
  void mapsToolDefinitionsAndEphemeralTranscriptWithoutExecutingCallbacks() {
    var chatModel =
        new StubChatModel(
            prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("完成")))));
    var call = new ToolCall("call-1", "lookup", Map.of("city", "上海"));
    var definition =
        new ToolDefinition(
            "lookup",
            "查询",
            Map.of(
                "type", "object",
                "properties", Map.of("city", Map.of("type", "string"))),
            ToolRisk.READ_ONLY);

    new SpringAiChatModelAdapter(chatModel)
        .generate(
            new ChatModelRequest(
                List.of(
                    new ChatMessage(MessageRole.USER, "问题"),
                    new AssistantToolCallMessage("查询中", List.of(call)),
                    new ToolResultMessage(
                        call.id(), call.name(), ToolResultStatus.SUCCESS, "晴")),
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
    var chatModel =
        new StubChatModel(prompt -> new ChatResponse(List.of(new Generation(output))));

    var response = new SpringAiChatModelAdapter(chatModel).generate(request());

    assertThat(response.content()).isEmpty();
    assertThat(response.toolCalls()).containsExactly(
        new ToolCall("call-1", "lookup", Map.of("city", "上海")));
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
    private Prompt lastPrompt;

    private StubChatModel(Function<Prompt, ChatResponse> response) {
      this.response = response;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      lastPrompt = prompt;
      return response.apply(prompt);
    }

    private Prompt lastPrompt() {
      return lastPrompt;
    }
  }
}
