package io.namei.agent.adapter.springai;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ModelInvocationException;
import io.namei.agent.kernel.error.ModelTimeoutException;
import io.namei.agent.kernel.model.AssistantToolCallMessage;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.ModelMessage;
import io.namei.agent.kernel.model.ToolResultMessage;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.ObjectMapper;

public final class SpringAiChatModelAdapter implements ChatModelPort {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final ChatModel chatModel;

  public SpringAiChatModelAdapter(ChatModel chatModel) {
    this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
  }

  @Override
  public ChatModelResponse generate(ChatModelRequest request) {
    Objects.requireNonNull(request, "request");
    try {
      List<Message> instructions = request.messages().stream().map(this::toSpringMessage).toList();
      var response = chatModel.call(prompt(instructions, request.tools()));
      if (response == null
          || response.getResult() == null
          || response.getResult().getOutput() == null) {
        throw new InvalidModelResponseException("模型响应缺少 Generation");
      }

      var output = response.getResult().getOutput();
      String text = output.getText();
      var toolCalls = parseToolCalls(output.getToolCalls());
      if ((text == null || text.isBlank()) && toolCalls.isEmpty()) {
        throw new InvalidModelResponseException("模型返回了空响应");
      }
      return new ChatModelResponse(text == null ? "" : text.strip(), toolCalls);
    } catch (InvalidModelResponseException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      if (hasTimeoutCause(exception)) {
        throw new ModelTimeoutException("模型调用超时", exception);
      }
      throw new ModelInvocationException("模型调用失败", exception);
    }
  }

  private Prompt prompt(List<Message> instructions, List<ToolDefinition> definitions) {
    if (definitions.isEmpty()) {
      return new Prompt(instructions);
    }
    List<ToolCallback> callbacks =
        definitions.stream().<ToolCallback>map(SchemaOnlyToolCallback::new).toList();
    var options = ToolCallingChatOptions.builder().toolCallbacks(callbacks).build();
    return new Prompt(instructions, options);
  }

  private Message toSpringMessage(ModelMessage message) {
    if (message instanceof ChatMessage chat) {
      return switch (chat.role()) {
        case SYSTEM -> new SystemMessage(chat.content());
        case USER -> new UserMessage(chat.content());
        case ASSISTANT -> new AssistantMessage(chat.content());
        case TOOL -> throw new IllegalArgumentException("普通消息不能使用 TOOL 角色");
      };
    }
    if (message instanceof AssistantToolCallMessage assistant) {
      var calls =
          assistant.toolCalls().stream()
              .map(
                  call ->
                      new AssistantMessage.ToolCall(
                          call.id(),
                          "function",
                          call.name(),
                          JSON.writeValueAsString(call.arguments())))
              .toList();
      return AssistantMessage.builder().content(assistant.content()).toolCalls(calls).build();
    }
    if (message instanceof ToolResultMessage result) {
      var response =
          new ToolResponseMessage.ToolResponse(
              result.toolCallId(), result.toolName(), result.content());
      return ToolResponseMessage.builder().responses(List.of(response)).build();
    }
    throw new IllegalArgumentException("不支持的模型消息类型");
  }

  private List<ToolCall> parseToolCalls(List<AssistantMessage.ToolCall> calls) {
    if (calls == null || calls.isEmpty()) {
      return List.of();
    }
    try {
      return calls.stream()
          .map(call -> new ToolCall(call.id(), call.name(), parseArguments(call.arguments())))
          .toList();
    } catch (RuntimeException exception) {
      throw new InvalidModelResponseException("模型 Tool Call 格式无效");
    }
  }

  private Map<String, Object> parseArguments(String arguments) {
    Object decoded = JSON.readValue(arguments == null ? "{}" : arguments, Object.class);
    if (!(decoded instanceof Map<?, ?> raw)) {
      throw new IllegalArgumentException("Tool Call arguments 必须是 JSON Object");
    }
    var result = new LinkedHashMap<String, Object>();
    raw.forEach((key, value) -> result.put(String.valueOf(key), value));
    return result;
  }

  private record SchemaOnlyToolCallback(ToolDefinition definition) implements ToolCallback {
    private SchemaOnlyToolCallback {
      Objects.requireNonNull(definition, "definition");
    }

    @Override
    public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
      return org.springframework.ai.tool.definition.ToolDefinition.builder()
          .name(definition.name())
          .description(definition.description())
          .inputSchema(JSON.writeValueAsString(definition.inputSchema()))
          .build();
    }

    @Override
    public String call(String toolInput) {
      throw new UnsupportedOperationException("工具必须由 agent-application 执行");
    }
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
