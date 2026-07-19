package io.namei.agent.adapter.springai;

import io.namei.agent.kernel.channel.MessageContract;
import io.namei.agent.kernel.concurrent.CancellationSignal;
import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ModelContextLimitException;
import io.namei.agent.kernel.error.ModelInvocationException;
import io.namei.agent.kernel.error.ModelSafetyRejectedException;
import io.namei.agent.kernel.error.ModelTimeoutException;
import io.namei.agent.kernel.error.TurnCancelledException;
import io.namei.agent.kernel.model.AssistantToolCallMessage;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.ModelMessage;
import io.namei.agent.kernel.model.ProviderCacheUsage;
import io.namei.agent.kernel.model.ToolResultMessage;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.ChatModelStreamObserver;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

public final class SpringAiChatModelAdapter implements ChatModelPort {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(30);
  private static final int MAX_STREAM_TOOL_CALLS = 128;
  private static final int MAX_FAILURE_CAUSES = 32;
  private static final List<String> SAFETY_MARKERS =
      List.of("data_inspection_failed", "content_filter", "content_policy_violation");
  private static final List<String> CONTEXT_LIMIT_MARKERS =
      List.of(
          "range of input length",
          "context_length_exceeded",
          "maximum context length",
          "context window exceeds limit",
          "string too long",
          "reduce the length",
          "too many tokens");
  private final ChatModel chatModel;
  private final int maxArgumentBytes;
  private final Duration streamIdleTimeout;
  private final OpenAiStreamCancellationRegistry streamCancellationRegistry;

  public SpringAiChatModelAdapter(ChatModel chatModel) {
    this(chatModel, 16_384, DEFAULT_STREAM_IDLE_TIMEOUT);
  }

  public SpringAiChatModelAdapter(ChatModel chatModel, int maxArgumentBytes) {
    this(chatModel, maxArgumentBytes, DEFAULT_STREAM_IDLE_TIMEOUT);
  }

  public SpringAiChatModelAdapter(
      ChatModel chatModel, int maxArgumentBytes, Duration streamIdleTimeout) {
    this(chatModel, maxArgumentBytes, streamIdleTimeout, null);
  }

  SpringAiChatModelAdapter(
      ChatModel chatModel,
      int maxArgumentBytes,
      Duration streamIdleTimeout,
      OpenAiStreamCancellationRegistry streamCancellationRegistry) {
    this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
    if (maxArgumentBytes < 1) {
      throw new IllegalArgumentException("Tool Arguments 字节上限必须大于零");
    }
    this.maxArgumentBytes = maxArgumentBytes;
    if (streamIdleTimeout == null || streamIdleTimeout.isZero() || streamIdleTimeout.isNegative()) {
      throw new IllegalArgumentException("模型流空闲超时必须为正数");
    }
    this.streamIdleTimeout = streamIdleTimeout;
    this.streamCancellationRegistry = streamCancellationRegistry;
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
      return new ChatModelResponse(
          text == null ? "" : text.strip(), toolCalls, cacheUsage(response));
    } catch (InvalidModelResponseException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw mapProviderFailure(exception, "模型调用超时", "模型调用失败");
    }
  }

  @Override
  public ChatModelResponse generate(
      ChatModelRequest request, ChatModelStreamObserver observer, CancellationSignal cancellation) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(observer, "observer");
    Objects.requireNonNull(cancellation, "cancellation");
    cancellation.throwIfCancellationRequested();

    List<Message> instructions = request.messages().stream().map(this::toSpringMessage).toList();
    var transport =
        streamCancellationRegistry == null
            ? OpenAiStreamCancellationRegistry.Registration.NONE
            : streamCancellationRegistry.open(chatModel);
    var bridge = new StreamingBridge(observer, transport::cancel);
    try (transport;
        var registration = cancellation.onCancellation(bridge::cancelFromSignal)) {
      if (bridge.isDone()) {
        return bridge.await();
      }

      Flux<org.springframework.ai.chat.model.ChatResponse> stream;
      try {
        stream =
            chatModel.stream(prompt(instructions, request.tools(), transport.requestHeaders()));
      } catch (RuntimeException exception) {
        throw mapStreamingFailure(exception);
      }
      if (stream == null) {
        throw new InvalidModelResponseException("模型流响应不能为空");
      }
      if (bridge.isDone()) {
        return bridge.await();
      }

      try {
        stream.timeout(streamIdleTimeout).limitRate(1).subscribe(bridge);
      } catch (RuntimeException exception) {
        if (!bridge.isDone()) {
          transport.cancel();
          throw mapStreamingFailure(exception);
        }
      }
      return bridge.await();
    }
  }

  private Prompt prompt(List<Message> instructions, List<ToolDefinition> definitions) {
    return prompt(instructions, definitions, Map.of());
  }

  private Prompt prompt(
      List<Message> instructions,
      List<ToolDefinition> definitions,
      Map<String, String> transportHeaders) {
    if (definitions.isEmpty()) {
      if (transportHeaders.isEmpty()) {
        return new Prompt(instructions);
      }
    }
    List<ToolCallback> callbacks =
        definitions.stream().<ToolCallback>map(SchemaOnlyToolCallback::new).toList();
    var configuredOptions = chatModel.getOptions();
    ToolCallingChatOptions options;
    if (configuredOptions instanceof OpenAiChatOptions openAiOptions) {
      var headers = new LinkedHashMap<String, String>();
      if (openAiOptions.getCustomHeaders() != null) {
        headers.putAll(openAiOptions.getCustomHeaders());
      }
      headers.putAll(transportHeaders);
      options = openAiOptions.mutate().customHeaders(headers).toolCallbacks(callbacks).build();
    } else {
      options =
          configuredOptions instanceof ToolCallingChatOptions toolOptions
              ? toolOptions.mutate().toolCallbacks(callbacks).build()
              : ToolCallingChatOptions.builder().toolCallbacks(callbacks).build();
    }
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

  private static ProviderCacheUsage cacheUsage(
      org.springframework.ai.chat.model.ChatResponse response) {
    if (response.getMetadata() == null || response.getMetadata().getUsage() == null) {
      return null;
    }
    var usage = response.getMetadata().getUsage();
    Integer promptTokens = usage.getPromptTokens();
    Long cacheHitTokens = usage.getCacheReadInputTokens();
    if (promptTokens == null || cacheHitTokens == null) {
      return null;
    }
    try {
      return new ProviderCacheUsage(promptTokens.longValue(), cacheHitTokens);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private Map<String, Object> parseArguments(String arguments) {
    String json = arguments == null ? "{}" : arguments;
    if (json.getBytes(StandardCharsets.UTF_8).length > maxArgumentBytes) {
      throw new IllegalArgumentException("Tool Call arguments 超过字节上限");
    }
    Object decoded = JSON.readValue(json, Object.class);
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
    for (Throwable current : causes(throwable)) {
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

  private static boolean hasMarker(Throwable throwable, List<String> markers) {
    for (Throwable current : causes(throwable)) {
      String message = current.getMessage();
      if (message == null) {
        continue;
      }
      String normalized = message.toLowerCase(Locale.ROOT);
      if (markers.stream().anyMatch(normalized::contains)) {
        return true;
      }
    }
    return false;
  }

  private static List<Throwable> causes(Throwable throwable) {
    var result = new java.util.ArrayList<Throwable>();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable current = throwable;
        current != null && result.size() < MAX_FAILURE_CAUSES && seen.add(current);
        current = current.getCause()) {
      result.add(current);
    }
    return result;
  }

  private static RuntimeException mapProviderFailure(
      Throwable failure, String timeoutMessage, String invocationMessage) {
    if (failure instanceof ModelSafetyRejectedException safety) {
      return safety;
    }
    if (failure instanceof ModelContextLimitException contextLimit) {
      return contextLimit;
    }
    if (failure instanceof ModelTimeoutException timeout) {
      return timeout;
    }
    if (failure instanceof ModelInvocationException invocation) {
      return invocation;
    }
    if (hasTimeoutCause(failure)) {
      return new ModelTimeoutException(timeoutMessage, failure);
    }
    if (hasMarker(failure, SAFETY_MARKERS)) {
      return new ModelSafetyRejectedException(failure);
    }
    if (hasMarker(failure, CONTEXT_LIMIT_MARKERS)) {
      return new ModelContextLimitException(failure);
    }
    return new ModelInvocationException(invocationMessage, failure);
  }

  private RuntimeException mapStreamingFailure(Throwable failure) {
    if (failure instanceof InvalidModelResponseException invalid) {
      return invalid;
    }
    if (failure instanceof TurnCancelledException cancelled) {
      return cancelled;
    }
    if (failure instanceof ModelTimeoutException timeout) {
      return timeout;
    }
    if (failure instanceof ModelInvocationException invocation) {
      return invocation;
    }
    return mapProviderFailure(failure, "模型流空闲超时", "模型流调用失败");
  }

  private final class StreamingBridge
      extends BaseSubscriber<org.springframework.ai.chat.model.ChatResponse> {
    private final Object lock = new Object();
    private final ChatModelStreamObserver observer;
    private final StringBuilder content = new StringBuilder();
    private final LinkedHashMap<String, ToolCall> toolCalls = new LinkedHashMap<>();
    private ProviderCacheUsage cacheUsage;
    private final CompletableFuture<ChatModelResponse> completion = new CompletableFuture<>();
    private final AtomicBoolean done = new AtomicBoolean();
    private final AtomicReference<RuntimeException> projectFailure = new AtomicReference<>();
    private int contentCodePoints;

    private final Runnable transportCancel;

    private StreamingBridge(ChatModelStreamObserver observer, Runnable transportCancel) {
      this.observer = observer;
      this.transportCancel = transportCancel;
    }

    @Override
    protected void hookOnSubscribe(org.reactivestreams.Subscription subscription) {
      if (done.get()) {
        cancel();
        return;
      }
      request(1);
    }

    @Override
    protected void hookOnNext(org.springframework.ai.chat.model.ChatResponse response) {
      try {
        synchronized (lock) {
          if (done.get()) {
            return;
          }
          accept(response);
        }
      } catch (RuntimeException exception) {
        failProject(exception);
        return;
      }
      if (!done.get()) {
        request(1);
      }
    }

    @Override
    protected void hookOnComplete() {
      try {
        ChatModelResponse response;
        synchronized (lock) {
          if (done.get()) {
            return;
          }
          String finalContent = content.toString().strip();
          if (finalContent.isBlank() && toolCalls.isEmpty()) {
            throw new InvalidModelResponseException("模型返回了空响应");
          }
          response =
              new ChatModelResponse(finalContent, List.copyOf(toolCalls.values()), cacheUsage);
          if (!done.compareAndSet(false, true)) {
            return;
          }
        }
        completion.complete(response);
      } catch (RuntimeException exception) {
        failProject(exception);
      }
    }

    @Override
    protected void hookOnError(Throwable failure) {
      synchronized (lock) {
        if (!done.compareAndSet(false, true)) {
          return;
        }
      }
      completion.completeExceptionally(failure);
      transportCancel.run();
    }

    private void accept(org.springframework.ai.chat.model.ChatResponse response) {
      if (response == null) {
        throw new InvalidModelResponseException("模型流响应不能为空");
      }
      if (response.getResults() == null || response.getResults().isEmpty()) {
        return;
      }
      var generation = response.getResult();
      if (generation == null || generation.getOutput() == null) {
        throw new InvalidModelResponseException("模型流响应缺少 Generation");
      }
      var output = generation.getOutput();
      var incomingCacheUsage = cacheUsage(response);
      if (incomingCacheUsage != null) {
        cacheUsage = incomingCacheUsage;
      }
      String delta = output.getText();
      if (delta != null && !delta.isEmpty()) {
        observer.onContentDelta(delta);
        int deltaCodePoints = delta.codePointCount(0, delta.length());
        if ((long) contentCodePoints + deltaCodePoints > MessageContract.MAX_CONTENT_CHARACTERS) {
          throw new InvalidModelResponseException("模型流文本超过聚合上限");
        }
        content.append(delta);
        contentCodePoints += deltaCodePoints;
      }
      List<AssistantMessage.ToolCall> rawToolCalls = output.getToolCalls();
      int incomingToolCalls = rawToolCalls == null ? 0 : rawToolCalls.size();
      if ((long) toolCalls.size() + incomingToolCalls > MAX_STREAM_TOOL_CALLS) {
        throw new InvalidModelResponseException("模型流 Tool Call 超过聚合上限");
      }
      for (ToolCall call : parseToolCalls(rawToolCalls)) {
        if (toolCalls.putIfAbsent(call.id(), call) != null) {
          throw new InvalidModelResponseException("模型流包含重复 Tool Call");
        }
      }
    }

    private void cancelFromSignal() {
      var cancelled = new TurnCancelledException("当前模型流已取消");
      failProject(cancelled);
    }

    private boolean failProject(RuntimeException failure) {
      synchronized (lock) {
        if (!done.compareAndSet(false, true)) {
          return false;
        }
        projectFailure.set(failure);
      }
      cancel();
      transportCancel.run();
      completion.completeExceptionally(failure);
      return true;
    }

    private boolean isDone() {
      return done.get();
    }

    private ChatModelResponse await() {
      try {
        return completion.get();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        var cancelled = new TurnCancelledException("模型流等待被中断");
        failProject(cancelled);
        throw cancelled;
      } catch (ExecutionException execution) {
        Throwable failure = execution.getCause();
        RuntimeException project = projectFailure.get();
        if (project != null && failure == project) {
          throw project;
        }
        throw mapStreamingFailure(failure);
      }
    }
  }
}
