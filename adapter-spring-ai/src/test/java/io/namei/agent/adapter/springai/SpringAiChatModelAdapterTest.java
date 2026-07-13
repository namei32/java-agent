package io.namei.agent.adapter.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ModelInvocationException;
import io.namei.agent.kernel.error.ModelTimeoutException;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.MessageRole;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class SpringAiChatModelAdapterTest {
  @Test
  void mapsAllProjectRolesAndReturnsTrimmedAssistantText() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(
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
    var promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());
    assertThat(promptCaptor.getValue().getInstructions())
        .extracting(message -> message.getMessageType())
        .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT);
    assertThat(promptCaptor.getValue().getInstructions())
        .extracting(message -> message.getText())
        .containsExactly("系统", "问题", "历史回答");
  }

  @Test
  void rejectsMissingResponseParts() {
    ChatModel nullResponseModel = mock(ChatModel.class);
    when(nullResponseModel.call(any(Prompt.class))).thenReturn(null);
    ChatModel missingGenerationModel = mock(ChatModel.class);
    when(missingGenerationModel.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of()));
    ChatModel missingOutputModel = mock(ChatModel.class);
    ChatResponse response = mock(ChatResponse.class);
    Generation generation = mock(Generation.class);
    when(missingOutputModel.call(any(Prompt.class))).thenReturn(response);
    when(response.getResult()).thenReturn(generation);
    when(generation.getOutput()).thenReturn(null);

    assertInvalidResponse(nullResponseModel, "缺少");
    assertInvalidResponse(missingGenerationModel, "缺少");
    assertInvalidResponse(missingOutputModel, "缺少");
  }

  @Test
  void rejectsBlankAssistantText() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(" \t ")))));

    assertInvalidResponse(chatModel, "空响应");
  }

  @Test
  void mapsTimeoutCauseChainToStableProjectException() {
    ChatModel chatModel = mock(ChatModel.class);
    var providerFailure = new IllegalStateException("provider", new SocketTimeoutException("slow"));
    when(chatModel.call(any(Prompt.class))).thenThrow(providerFailure);

    assertThatThrownBy(() -> new SpringAiChatModelAdapter(chatModel).generate(request()))
        .isInstanceOf(ModelTimeoutException.class)
        .hasMessage("模型调用超时")
        .hasCause(providerFailure);
  }

  @Test
  void mapsOtherProviderFailuresToStableProjectException() {
    ChatModel chatModel = mock(ChatModel.class);
    var providerFailure = new IllegalStateException("provider payload must stay internal");
    when(chatModel.call(any(Prompt.class))).thenThrow(providerFailure);

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
}
