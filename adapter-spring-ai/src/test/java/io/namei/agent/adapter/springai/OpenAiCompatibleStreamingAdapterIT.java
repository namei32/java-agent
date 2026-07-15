package io.namei.agent.adapter.springai;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.namei.agent.kernel.concurrent.CancellationSignal;
import io.namei.agent.kernel.error.TurnCancelledException;
import io.namei.agent.kernel.model.AssistantToolCallMessage;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.ToolResultMessage;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.io.IOException;
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

@SpringBootTest(classes = OpenAiCompatibleStreamingAdapterIT.TestApplication.class)
class OpenAiCompatibleStreamingAdapterIT {
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
    registry.add("spring.ai.openai.chat.timeout", () -> "5s");
    registry.add("spring.ai.openai.chat.max-retries", () -> "0");
    registry.add("spring.ai.model.embedding", () -> "none");
    registry.add("agent.model.stream-idle-timeout", () -> "3s");
  }

  @Test
  void streamsRealSseChunksAndReturnsAuthoritativeCompletion() throws Exception {
    SERVER.respondSse(
        List.of(
            OpenAiStubServer.textDelta("你"),
            OpenAiStubServer.textDelta("好"),
            OpenAiStubServer.finished("stop")));
    var deltas = new ArrayList<String>();

    ChatModelResponse result = model.generate(request(), deltas::add, CancellationSignal.none());

    assertThat(deltas).containsExactly("你", "好");
    assertThat(result.content()).isEqualTo("你好");
    assertThat(SERVER.requestBodies()).singleElement();
    var requestBody = JSON.readTree(SERVER.requestBodies().getFirst());
    assertThat(requestBody.path("stream").asBoolean()).isTrue();
    assertThat(requestBody.path("model").asString()).isEqualTo("test-model");
    assertThat(SERVER.internalCorrelationHeaderObserved()).isFalse();
  }

  @Test
  void streamsToolCallThenSendsAssistantCallAndToolResultOnContinuation() throws Exception {
    var definition =
        new ToolDefinition(
            "current_time",
            "返回当前 UTC 时间",
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
            ToolRisk.READ_ONLY);
    SERVER.respondSse(
        List.of(
            OpenAiStubServer.toolCallStart("call-1", "current_time"),
            OpenAiStubServer.toolArguments("{}"),
            OpenAiStubServer.finished("tool_calls")));
    var firstDeltas = new ArrayList<String>();

    ChatModelResponse first =
        model.generate(
            new ChatModelRequest(
                List.of(new ChatMessage(MessageRole.USER, "现在几点？")), List.of(definition)),
            firstDeltas::add,
            CancellationSignal.none());

    assertThat(firstDeltas).isEmpty();
    assertThat(first.toolCalls()).containsExactly(new ToolCall("call-1", "current_time", Map.of()));
    ToolCall call = first.toolCalls().getFirst();
    SERVER.respondSse(
        List.of(
            OpenAiStubServer.textDelta("现在是"),
            OpenAiStubServer.textDelta("固定时间"),
            OpenAiStubServer.finished("stop")));
    var secondDeltas = new ArrayList<String>();

    ChatModelResponse second =
        model.generate(
            new ChatModelRequest(
                List.of(
                    new ChatMessage(MessageRole.USER, "现在几点？"),
                    new AssistantToolCallMessage("", List.of(call)),
                    new ToolResultMessage(call, ToolResult.success("2026-07-14T04:00:00Z"))),
                List.of(definition)),
            secondDeltas::add,
            CancellationSignal.none());

    assertThat(secondDeltas).containsExactly("现在是", "固定时间");
    assertThat(second.content()).isEqualTo("现在是固定时间");
    assertThat(SERVER.requestBodies()).hasSize(2);
    var firstRequest = JSON.readTree(SERVER.requestBodies().getFirst());
    assertThat(firstRequest.path("stream").asBoolean()).isTrue();
    assertThat(firstRequest.path("tools").get(0).path("function").path("name").asString())
        .isEqualTo("current_time");
    var secondRequest = JSON.readTree(SERVER.requestBodies().get(1));
    assertThat(
            secondRequest.path("messages").get(1).path("tool_calls").get(0).path("id").asString())
        .isEqualTo("call-1");
    assertThat(secondRequest.path("messages").get(2).path("role").asString()).isEqualTo("tool");
    assertThat(secondRequest.path("messages").get(2).path("tool_call_id").asString())
        .isEqualTo("call-1");
  }

  @Test
  void cancellationStopsObserverAndClosesRealSseConnection() throws Exception {
    SERVER.respondSse(
        Duration.ofSeconds(1),
        List.of(
            OpenAiStubServer.textDelta("部分"),
            OpenAiStubServer.textDelta("迟到"),
            OpenAiStubServer.finished("stop")));
    var cancellation = new TestCancellation();
    var deltas = new CopyOnWriteArrayList<String>();
    var firstDelta = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<ChatModelResponse> future =
          executor.submit(
              () ->
                  model.generate(
                      request(),
                      delta -> {
                        deltas.add(delta);
                        firstDelta.countDown();
                      },
                      cancellation));
      assertThat(firstDelta.await(2, SECONDS)).isTrue();

      cancellation.cancel();

      assertThatThrownBy(() -> awaitResult(future)).isInstanceOf(TurnCancelledException.class);
    }

    await().atMost(Duration.ofSeconds(4)).until(SERVER::clientDisconnected);
    assertThat(deltas).containsExactly("部分");
  }

  @Test
  void cancellationClosesOnlyTheTargetSseConnection() throws Exception {
    SERVER.respondSse(
        Duration.ofMillis(250),
        List.of(
            OpenAiStubServer.textDelta("首段"),
            OpenAiStubServer.textDelta("尾段"),
            OpenAiStubServer.finished("stop")));
    var firstCancellation = new TestCancellation();
    var firstDeltas = new CopyOnWriteArrayList<String>();
    var secondDeltas = new CopyOnWriteArrayList<String>();
    var firstStarted = new CountDownLatch(1);
    var secondStarted = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<ChatModelResponse> cancelled =
          executor.submit(
              () ->
                  model.generate(
                      request(),
                      delta -> {
                        firstDeltas.add(delta);
                        firstStarted.countDown();
                      },
                      firstCancellation));
      Future<ChatModelResponse> surviving =
          executor.submit(
              () ->
                  model.generate(
                      request(),
                      delta -> {
                        secondDeltas.add(delta);
                        secondStarted.countDown();
                      },
                      CancellationSignal.none()));
      assertThat(firstStarted.await(2, SECONDS)).isTrue();
      assertThat(secondStarted.await(2, SECONDS)).isTrue();

      firstCancellation.cancel();

      assertThatThrownBy(() -> awaitResult(cancelled)).isInstanceOf(TurnCancelledException.class);
      assertThat(awaitResult(surviving).content()).isEqualTo("首段尾段");
    }

    assertThat(firstDeltas).containsExactly("首段");
    assertThat(secondDeltas).containsExactly("首段", "尾段");
    assertThat(SERVER.requestBodies()).hasSize(2);
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
      throw new AssertionError("等待取消结果超时", exception);
    }
  }

  private static ChatModelRequest request() {
    return new ChatModelRequest(List.of(new ChatMessage(MessageRole.USER, "问题")));
  }

  private static OpenAiStubServer createServer() {
    try {
      return new OpenAiStubServer();
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
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

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(SpringAiAdapterConfiguration.class)
  static class TestApplication {}
}
