package io.namei.agent.kernel.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.namei.agent.kernel.concurrent.CancellationSignal;
import io.namei.agent.kernel.error.TurnCancelledException;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.tool.ToolCall;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ProviderStreamingContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ChatModelRequest REQUEST =
      new ChatModelRequest(List.of(new ChatMessage(MessageRole.USER, "问题")));

  @Test
  void executesEveryKernelStreamingFixtureCaseAgainstProductionPort() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("message-bus/provider-streaming-cli.json"));
    JsonNode cases = fixture.path("kernelCases");

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(cases).hasSize(6);

    for (JsonNode testCase : cases) {
      execute(testCase.path("id").asString(), testCase.path("input"), testCase.path("expected"));
    }
  }

  @Test
  void neverCancelledSignalDoesNotInvokeCallbacks() {
    var callbacks = new AtomicInteger();
    try (var registration = CancellationSignal.none().onCancellation(callbacks::incrementAndGet)) {
      assertThat(CancellationSignal.none().isCancellationRequested()).isFalse();
    }
    assertThat(callbacks).hasValue(0);
  }

  private static void execute(String caseId, JsonNode input, JsonNode expected) {
    var generateCalls = new AtomicInteger();
    var deltas = new ArrayList<String>();
    ChatModelPort model =
        request -> {
          generateCalls.incrementAndGet();
          return response(input.path("responseKind").asString());
        };
    ChatModelStreamObserver observer = deltas::add;
    CancellationSignal cancellation =
        input.path("cancelled").asBoolean(false)
            ? new AlreadyCancelledSignal()
            : CancellationSignal.none();

    String outcome;
    ChatModelResponse result = null;
    try {
      result =
          switch (input.path("action").asString()) {
            case "generate" -> model.generate(REQUEST, observer, cancellation);
            case "null-request" -> model.generate(null, observer, cancellation);
            case "null-observer" -> model.generate(REQUEST, null, cancellation);
            case "null-cancellation" -> model.generate(REQUEST, observer, null);
            default -> throw new AssertionError("未知 Fixture Action: " + caseId);
          };
      outcome = "COMPLETED";
    } catch (TurnCancelledException exception) {
      outcome = "CANCELLED";
    } catch (IllegalArgumentException | NullPointerException exception) {
      outcome = "INVALID_ARGUMENT";
    }

    assertThat(outcome).as(caseId).isEqualTo(expected.path("outcome").asString());
    assertThat(generateCalls).as(caseId).hasValue(expected.path("generateCalls").asInt(0));
    assertThat(deltas).as(caseId).containsExactlyElementsOf(strings(expected.path("deltas")));
    if (result != null) {
      assertThat(result.content()).as(caseId).isEqualTo(expected.path("content").asString());
      assertThat(result.toolCalls()).as(caseId).hasSize(expected.path("toolCalls").asInt());
    }
  }

  private static ChatModelResponse response(String kind) {
    return switch (kind) {
      case "text" -> new ChatModelResponse("完整回答");
      case "tool" -> new ChatModelResponse("", List.of(new ToolCall("call-1", "lookup", Map.of())));
      default -> throw new IllegalArgumentException("未知响应类型");
    };
  }

  private static List<String> strings(JsonNode values) {
    if (!values.isArray()) {
      return List.of();
    }
    var result = new ArrayList<String>();
    values.forEach(value -> result.add(value.asString()));
    return result;
  }

  private static Path goldenRoot() {
    String configured = System.getProperty("golden.root");
    assertThat(configured).as("Maven 必须提供 golden.root").isNotBlank();
    return Path.of(configured).toAbsolutePath().normalize();
  }

  private static final class AlreadyCancelledSignal implements CancellationSignal {
    @Override
    public boolean isCancellationRequested() {
      return true;
    }

    @Override
    public Registration onCancellation(Runnable callback) {
      if (callback == null) {
        fail("callback 不能为空");
      }
      callback.run();
      return () -> {};
    }
  }
}
