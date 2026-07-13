package io.namei.agent.adapter.springai;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ModelInvocationException;
import io.namei.agent.kernel.error.ModelTimeoutException;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.port.ChatModelPort;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

public final class SpringAiChatModelAdapter implements ChatModelPort {
  private final ChatModel chatModel;

  public SpringAiChatModelAdapter(ChatModel chatModel) {
    this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
  }

  @Override
  public ChatModelResponse generate(ChatModelRequest request) {
    Objects.requireNonNull(request, "request");
    try {
      List<Message> instructions = request.messages().stream().map(this::toSpringMessage).toList();
      var response = chatModel.call(new Prompt(instructions));
      if (response == null
          || response.getResult() == null
          || response.getResult().getOutput() == null) {
        throw new InvalidModelResponseException("模型响应缺少 Generation");
      }

      String text = response.getResult().getOutput().getText();
      if (text == null || text.isBlank()) {
        throw new InvalidModelResponseException("模型返回了空响应");
      }
      return new ChatModelResponse(text.strip());
    } catch (InvalidModelResponseException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      if (hasTimeoutCause(exception)) {
        throw new ModelTimeoutException("模型调用超时", exception);
      }
      throw new ModelInvocationException("模型调用失败", exception);
    }
  }

  private Message toSpringMessage(ChatMessage message) {
    return switch (message.role()) {
      case SYSTEM -> new SystemMessage(message.content());
      case USER -> new UserMessage(message.content());
      case ASSISTANT -> new AssistantMessage(message.content());
    };
  }

  private static boolean hasTimeoutCause(Throwable throwable) {
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      boolean interruptedTimeout =
          current instanceof InterruptedIOException
              && current.getMessage() != null
              && current.getMessage().toLowerCase(Locale.ROOT).contains("timeout");
      if (current instanceof SocketTimeoutException
          || current instanceof HttpTimeoutException
          || interruptedTimeout
          || current.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("timeout")) {
        return true;
      }
    }
    return false;
  }
}
