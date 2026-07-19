package io.namei.agent.adapter.springai;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.namei.agent.kernel.channel.MessageContract;
import io.namei.agent.kernel.concurrent.CancellationSignal;
import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ModelInvocationException;
import io.namei.agent.kernel.error.ModelTimeoutException;
import io.namei.agent.kernel.error.TurnCancelledException;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

class SpringAiStreamingChatModelAdapterTest {
  @Test
  void emitsTextChunksAndPreservesProviderOptionsWhenAddingToolSchema() {
    var options = OpenAiChatOptions.builder().model("provider-model").temperature(0.25).build();
    var chatModel =
        new StreamingStubChatModel(
            options,
            ignored -> Flux.just(new ChatResponse(List.of()), response("你"), response("好")));
    var deltas = new ArrayList<String>();
    var definition =
        new ToolDefinition(
            "lookup", "查询", Map.of("type", "object", "properties", Map.of()), ToolRisk.READ_ONLY);

    ChatModelResponse result =
        new SpringAiChatModelAdapter(chatModel, 16_384, Duration.ofSeconds(1))
            .generate(request(List.of(definition)), deltas::add, CancellationSignal.none());

    assertThat(deltas).containsExactly("你", "好");
    assertThat(result.content()).isEqualTo("你好");
    assertThat(chatModel.lastPrompt().getOptions())
        .isInstanceOfSatisfying(
            OpenAiChatOptions.class,
            actual -> {
              assertThat(actual.getModel()).isEqualTo("provider-model");
              assertThat(actual.getTemperature()).isEqualTo(0.25);
              assertThat(actual.getToolCallbacks()).singleElement();
              assertThat(actual.getToolCallbacks().getFirst().getToolDefinition().name())
                  .isEqualTo("lookup");
            });
  }

  @Test
  void aggregatesCompleteToolCallsWithoutPublishingArgumentsAsText() {
    var first = toolResponse("call-1", "lookup", "{\"city\":\"上海\"}");
    var second = toolResponse("call-2", "clock", "{}");
    var chatModel = new StreamingStubChatModel(null, ignored -> Flux.just(first, second));
    var deltas = new ArrayList<String>();

    ChatModelResponse result =
        new SpringAiChatModelAdapter(chatModel, 16_384, Duration.ofSeconds(1))
            .generate(request(List.of()), deltas::add, CancellationSignal.none());

    assertThat(deltas).isEmpty();
    assertThat(result.content()).isEmpty();
    assertThat(result.toolCalls())
        .containsExactly(
            new ToolCall("call-1", "lookup", Map.of("city", "上海")),
            new ToolCall("call-2", "clock", Map.of()));
  }

  @Test
  void keepsTheLastUsableStandardUsageFromACompletedStream() {
    var chatModel =
        new StreamingStubChatModel(
            null, ignored -> Flux.just(response("前", 4, 1), response("中"), response("后", 10, 3)));

    ChatModelResponse result =
        new SpringAiChatModelAdapter(chatModel, 16_384, Duration.ofSeconds(1))
            .generate(request(List.of()), ignored -> {}, CancellationSignal.none());

    assertThat(result.content()).isEqualTo("前中后");
    assertThat(result.cacheUsage())
        .contains(new io.namei.agent.kernel.model.ProviderCacheUsage(10, 3));
  }

  @Test
  @Tag("failure")
  void cancellationDisposesSubscriptionAndRejectsLateCompletion() throws Exception {
    var disposed = new AtomicBoolean();
    var firstDelta = new CountDownLatch(1);
    var upstreamSubscribed = new CountDownLatch(1);
    var cleanupStarted = new CountDownLatch(1);
    var releaseCleanup = new CountDownLatch(1);
    Flux<ChatResponse> stream =
        Flux.concat(
            Flux.just(response("部分")),
            Flux.<ChatResponse>never()
                .doOnSubscribe(ignored -> upstreamSubscribed.countDown())
                .doOnCancel(
                    () -> {
                      cleanupStarted.countDown();
                      awaitCleanupRelease(releaseCleanup);
                      disposed.set(true);
                    }));
    var chatModel = new StreamingStubChatModel(null, ignored -> stream);
    var cancellation = new TestCancellation();
    var deltas = new CopyOnWriteArrayList<String>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<ChatModelResponse> future =
          executor.submit(
              () ->
                  new SpringAiChatModelAdapter(chatModel, 16_384, Duration.ofSeconds(5))
                      .generate(
                          request(List.of()),
                          delta -> {
                            deltas.add(delta);
                            firstDelta.countDown();
                          },
                          cancellation));
      await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> firstDelta.getCount() == 0 && upstreamSubscribed.getCount() == 0);

      Future<?> cancellationFuture = executor.submit(cancellation::cancel);
      try {
        await().atMost(Duration.ofSeconds(5)).until(() -> cleanupStarted.getCount() == 0);
        assertThat(future.isDone()).isFalse();
      } finally {
        releaseCleanup.countDown();
      }

      cancellationFuture.get(2, SECONDS);
      assertThatThrownBy(() -> awaitResult(future))
          .isInstanceOf(TurnCancelledException.class)
          .hasMessageNotContaining("provider");
    }

    assertThat(deltas).containsExactly("部分");
    assertThat(disposed).isTrue();
  }

  @Test
  @Tag("failure")
  void preservesObserverRuntimeFailureIdentityAndCancelsUpstream() {
    var disposed = new AtomicBoolean();
    var observerFailure = new ObserverFailure();
    var chatModel =
        new StreamingStubChatModel(
            null,
            ignored ->
                Flux.just(response("一"), response("二")).doOnCancel(() -> disposed.set(true)));
    var seen = new ArrayList<String>();

    assertThatThrownBy(
            () ->
                new SpringAiChatModelAdapter(chatModel, 16_384, Duration.ofSeconds(1))
                    .generate(
                        request(List.of()),
                        delta -> {
                          seen.add(delta);
                          throw observerFailure;
                        },
                        CancellationSignal.none()))
        .isSameAs(observerFailure);

    assertThat(seen).containsExactly("一");
    assertThat(disposed).isTrue();
  }

  @Test
  @Tag("failure")
  void mapsIdleTimeoutToStableModelTimeoutAndDisposesUpstream() {
    var disposed = new AtomicBoolean();
    var chatModel =
        new StreamingStubChatModel(
            null, ignored -> Flux.<ChatResponse>never().doOnCancel(() -> disposed.set(true)));

    assertThatThrownBy(
            () ->
                new SpringAiChatModelAdapter(chatModel, 16_384, Duration.ofMillis(25))
                    .generate(request(List.of()), ignored -> {}, CancellationSignal.none()))
        .isInstanceOf(ModelTimeoutException.class)
        .hasMessage("模型流空闲超时");

    assertThat(disposed).isTrue();
  }

  @Test
  @Tag("failure")
  void rejectsMissingOutputAndEmptyStreamAsInvalidModelResponses() {
    var missingOutput =
        new StreamingStubChatModel(
            null, ignored -> Flux.just(new ChatResponse(List.of(new Generation(null)))));
    var empty = new StreamingStubChatModel(null, ignored -> Flux.empty());

    assertStreamingInvalid(missingOutput);
    assertStreamingInvalid(empty);
  }

  @Test
  @Tag("failure")
  void mapsProviderStreamFailureWithoutLeakingPayload() {
    var providerFailure = new IllegalStateException("Bearer provider-secret");
    var chatModel = new StreamingStubChatModel(null, ignored -> Flux.error(providerFailure));

    assertThatThrownBy(
            () ->
                new SpringAiChatModelAdapter(chatModel, 16_384, Duration.ofSeconds(1))
                    .generate(request(List.of()), ignored -> {}, CancellationSignal.none()))
        .isInstanceOf(ModelInvocationException.class)
        .hasMessage("模型流调用失败")
        .hasMessageNotContaining("provider-secret")
        .hasCause(providerFailure);
  }

  @Test
  @Tag("failure")
  void rejectsDuplicateToolCallIdentifiersAsDamagedStream() {
    var chatModel =
        new StreamingStubChatModel(
            null,
            ignored ->
                Flux.just(
                    toolResponse("call-1", "lookup", "{}"),
                    toolResponse("call-1", "lookup", "{}")));

    assertStreamingInvalid(chatModel);
  }

  @Test
  @Tag("failure")
  void boundsDirectTextAggregationEvenWhenObserverDoesNotEnforceApplicationBudget() {
    String oversized = "a".repeat(MessageContract.MAX_CONTENT_CHARACTERS + 1);
    var chatModel = new StreamingStubChatModel(null, ignored -> Flux.just(response(oversized)));

    assertStreamingInvalid(chatModel);
  }

  @Test
  @Tag("failure")
  void boundsToolCallAggregationBeforeBuildingProjectResponse() {
    var responses =
        IntStream.range(0, 129)
            .mapToObj(index -> toolResponse("call-" + index, "lookup", "{}"))
            .toList();
    var chatModel = new StreamingStubChatModel(null, ignored -> Flux.fromIterable(responses));

    assertStreamingInvalid(chatModel);
  }

  private static void assertStreamingInvalid(ChatModel chatModel) {
    assertThatThrownBy(
            () ->
                new SpringAiChatModelAdapter(chatModel, 16_384, Duration.ofSeconds(1))
                    .generate(request(List.of()), ignored -> {}, CancellationSignal.none()))
        .isInstanceOf(InvalidModelResponseException.class);
  }

  private static ChatModelRequest request(List<ToolDefinition> tools) {
    return new ChatModelRequest(List.of(new ChatMessage(MessageRole.USER, "问题")), tools);
  }

  private static ChatResponse response(String content) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
  }

  private static ChatResponse response(String content, int promptTokens, long cacheHitTokens) {
    return new ChatResponse(
        List.of(new Generation(new AssistantMessage(content))),
        ChatResponseMetadata.builder()
            .usage(new DefaultUsage(promptTokens, 0, promptTokens, null, cacheHitTokens, null))
            .build());
  }

  private static ChatResponse toolResponse(String callId, String name, String arguments) {
    var output =
        AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", name, arguments)))
            .build();
    return new ChatResponse(List.of(new Generation(output)));
  }

  private static ChatModelResponse awaitResult(Future<ChatModelResponse> future) {
    try {
      return future.get(2, SECONDS);
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new AssertionError(exception.getCause());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    } catch (TimeoutException exception) {
      throw new AssertionError("等待流式结果超时", exception);
    }
  }

  private static void awaitCleanupRelease(CountDownLatch latch) {
    try {
      if (!latch.await(5, SECONDS)) {
        throw new AssertionError("Timed out waiting for controlled stream cleanup");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }

  private static final class StreamingStubChatModel implements ChatModel {
    private final ChatOptions options;
    private final Function<Prompt, Flux<ChatResponse>> stream;
    private Prompt lastPrompt;

    private StreamingStubChatModel(
        ChatOptions options, Function<Prompt, Flux<ChatResponse>> stream) {
      this.options = options;
      this.stream = stream;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      throw new AssertionError("流式入口不能退化为同步 call");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      lastPrompt = prompt;
      return stream.apply(prompt);
    }

    @Override
    public ChatOptions getOptions() {
      return options;
    }

    private Prompt lastPrompt() {
      return lastPrompt;
    }
  }

  private static final class TestCancellation implements CancellationSignal {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();

    @Override
    public boolean isCancellationRequested() {
      return cancelled.get();
    }

    @Override
    public Registration onCancellation(Runnable callback) {
      Objects.requireNonNull(callback, "callback");
      if (cancelled.get()) {
        callback.run();
        return () -> {};
      }
      callbacks.add(callback);
      if (cancelled.get() && callbacks.remove(callback)) {
        callback.run();
      }
      return () -> callbacks.remove(callback);
    }

    private void cancel() {
      if (!cancelled.compareAndSet(false, true)) {
        return;
      }
      callbacks.forEach(Runnable::run);
      callbacks.clear();
    }
  }

  private static final class ObserverFailure extends RuntimeException {}
}
