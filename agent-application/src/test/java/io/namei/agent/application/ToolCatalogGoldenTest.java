package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.approval.ApprovalDecision;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.ToolResultMessage;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.port.TurnLifecycleObserver;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ToolCatalogGoldenTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC);
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void executesToolCatalogFixtureAgainstProductionCatalogAndLoop() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("tools/tool-catalog-v1.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("source").asText()).isEqualTo("java-contract");
    for (JsonNode testCase : fixture.path("cases")) {
      verify(testCase);
    }
  }

  private static void verify(JsonNode testCase) {
    String id = testCase.path("id").asText();
    JsonNode input = testCase.path("input");
    JsonNode expected = testCase.path("expected");
    switch (id) {
      case "initial-always-on-and-search" -> {
        ToolRegistry registry = registry(catalog(new ArrayList<>()), mode(input));
        assertThat(names(registry.definitions(registry.newCatalogSession())))
            .isEqualTo(strings(expected.path("definitions")));
      }
      case "cjk-search-unlocks-deferred" -> {
        ToolCatalog catalog = catalog(new ArrayList<>());
        ToolCatalogSearchResult result =
            catalog.search(
                catalog.newSession(), input.path("query").asText(), input.path("topK").asInt());
        assertThat(result.matched())
            .extracting(ToolCatalogMatch::name)
            .isEqualTo(strings(expected.path("matched")));
        assertThat(result.unlocked()).isEqualTo(strings(expected.path("unlocked")));
      }
      case "select-exact-and-already-loaded" -> {
        ToolCatalog catalog = catalog(new ArrayList<>());
        ToolCatalogSession session = catalog.newSession();
        catalog.search(session, "日历", 5);
        ToolCatalogSearchResult result =
            catalog.search(session, input.path("query").asText(), input.path("topK").asInt());
        assertThat(result.unlocked()).isEqualTo(strings(expected.path("unlocked")));
        assertThat(result.alreadyLoaded()).isEqualTo(strings(expected.path("alreadyLoaded")));
      }
      case "hidden-tool-preflight-fails-closed" -> {
        ToolRegistry registry = registry(catalog(new ArrayList<>()), ToolRuntimeMode.READ_ONLY);
        assertThat(
                registry
                    .prepare(
                        List.of(new ToolCall("hidden", input.path("tool").asText(), Map.of())),
                        registry.newCatalogSession())
                    .getFirst()
                    .preflightFailure()
                    .content())
            .isEqualTo(expected.path("result").asText());
      }
      case "next-request-receives-unlocked-schema" -> {
        var executions = new ArrayList<String>();
        var model =
            new ScriptedModel(
                new ChatModelResponse(
                    "",
                    List.of(
                        new ToolCall(
                            "search",
                            "tool_search",
                            Map.of("query", input.path("query").asText())))),
                new ChatModelResponse("完成"));
        loop(catalog(executions), model).complete(List.of(new ChatMessage(MessageRole.USER, "问题")));
        assertThat(model.requests)
            .extracting(request -> names(request.tools()))
            .isEqualTo(stringMatrix(expected.path("requests")));
      }
      case "same-batch-cannot-execute-hidden-tool" -> {
        var executions = new ArrayList<String>();
        var model =
            new ScriptedModel(
                new ChatModelResponse(
                    "",
                    List.of(
                        new ToolCall(
                            "search", "tool_search", Map.of("query", input.path("query").asText())),
                        new ToolCall("lookup", "calendar_lookup", Map.of()))),
                new ChatModelResponse("完成"));
        loop(catalog(executions), model).complete(List.of(new ChatMessage(MessageRole.USER, "问题")));
        ToolResultMessage hidden =
            model.requests.get(1).messages().stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .toList()
                .get(1);
        assertThat(hidden.status().name()).isEqualTo(expected.path("hiddenStatus").asText());
        assertThat(executions).isEqualTo(strings(expected.path("executions")));
      }
      case "disabled-hides-catalog" -> {
        ToolRegistry registry = registry(catalog(new ArrayList<>()), mode(input));
        assertThat(names(registry.definitions(registry.newCatalogSession())))
            .isEqualTo(strings(expected.path("definitions")));
      }
      default -> throw new AssertionError("未知 Tool Catalog Fixture Case: " + id);
    }
  }

  private static ToolRuntimeMode mode(JsonNode input) {
    return ToolRuntimeMode.valueOf(input.path("mode").asText());
  }

  private static ToolRegistry registry(ToolCatalog catalog, ToolRuntimeMode mode) {
    return new ToolRegistry(
        catalog, new ToolRuntimeSettings(mode, 8, 16, Duration.ofSeconds(5), 32, 20_000));
  }

  private static ToolLoop loop(ToolCatalog catalog, ChatModelPort model) {
    ToolRuntimeSettings settings = ToolRuntimeSettings.readOnlyDefaults();
    ToolRegistry registry = new ToolRegistry(catalog, settings);
    LifecyclePublisher lifecycle = new LifecyclePublisher(TurnLifecycleObserver.noop());
    return new ToolLoop(
        model,
        registry,
        lifecycle,
        3,
        settings,
        new SideEffectBatchCoordinator(
            registry,
            request -> ApprovalDecision.deniedFor(request, CLOCK.instant(), "deny-all"),
            ToolExecutionPolicy.registeredRisk(),
            CLOCK,
            Duration.ofMinutes(5),
            new SecureIdGenerator(),
            SideEffectLedger.unavailable(),
            lifecycle));
  }

  private static ToolCatalog catalog(List<String> executions) {
    return new ToolCatalog(
        List.of(
            new ToolCatalogEntry(
                tool("current_time", "返回当前 UTC 时间", executions, false),
                ToolCatalogVisibility.ALWAYS_ON,
                ToolCatalogSource.BUILTIN,
                "",
                List.of("当前时间")),
            new ToolCatalogEntry(
                tool("calendar_lookup", "查询日历 calendar event", executions, true),
                ToolCatalogVisibility.DEFERRED,
                ToolCatalogSource.MCP,
                "calendar",
                List.of("日历")),
            new ToolCatalogEntry(
                tool("weather_lookup", "查询天气 weather", executions, true),
                ToolCatalogVisibility.DEFERRED,
                ToolCatalogSource.MCP,
                "weather",
                List.of("天气"))));
  }

  private static Tool tool(
      String name, String description, List<String> executions, boolean record) {
    return new Tool() {
      private final ToolDefinition definition =
          new ToolDefinition(
              name,
              description,
              Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
              ToolRisk.READ_ONLY);

      @Override
      public ToolDefinition definition() {
        return definition;
      }

      @Override
      public ToolResult execute(Map<String, Object> arguments) {
        if (record) {
          executions.add(name);
        }
        return ToolResult.success("ok");
      }
    };
  }

  private static List<String> names(List<ToolDefinition> definitions) {
    return definitions.stream().map(ToolDefinition::name).toList();
  }

  private static List<String> strings(JsonNode values) {
    var result = new ArrayList<String>();
    values.forEach(value -> result.add(value.asText()));
    return result;
  }

  private static List<List<String>> stringMatrix(JsonNode values) {
    var result = new ArrayList<List<String>>();
    values.forEach(value -> result.add(strings(value)));
    return result;
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }

  private static final class ScriptedModel implements ChatModelPort {
    private final ArrayDeque<ChatModelResponse> responses;
    private final List<ChatModelRequest> requests = new ArrayList<>();

    private ScriptedModel(ChatModelResponse... responses) {
      this.responses = new ArrayDeque<>(List.of(responses));
    }

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
      requests.add(request);
      return responses.removeFirst();
    }
  }
}
