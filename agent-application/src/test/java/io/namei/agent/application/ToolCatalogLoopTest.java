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
import io.namei.agent.kernel.tool.ToolResultStatus;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolCatalogLoopTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void exposesUnlockedDefinitionsOnlyToTheFollowingModelRequest() {
    var executions = new ArrayList<String>();
    var model =
        new ScriptedModel(
            new ChatModelResponse(
                "", List.of(new ToolCall("search", "tool_search", Map.of("query", "日历")))),
            new ChatModelResponse("", List.of(new ToolCall("lookup", "calendar_lookup", Map.of()))),
            new ChatModelResponse("完成"));

    String answer =
        loop(catalog(executions), model)
            .complete(List.of(new ChatMessage(MessageRole.USER, "查日历")));

    assertThat(answer).isEqualTo("完成");
    assertThat(model.requests)
        .extracting(request -> names(request.tools()))
        .containsExactly(
            List.of("current_time", "tool_search"),
            List.of("current_time", "tool_search", "calendar_lookup"),
            List.of("current_time", "tool_search", "calendar_lookup"));
    assertThat(executions).containsExactly("calendar_lookup");
  }

  @Test
  void doesNotAllowSearchAndHiddenToolExecutionInTheSameModelResponse() {
    var executions = new ArrayList<String>();
    var model =
        new ScriptedModel(
            new ChatModelResponse(
                "",
                List.of(
                    new ToolCall("search", "tool_search", Map.of("query", "日历")),
                    new ToolCall("lookup", "calendar_lookup", Map.of()))),
            new ChatModelResponse("已说明"));

    String answer =
        loop(catalog(executions), model)
            .complete(List.of(new ChatMessage(MessageRole.USER, "查日历")));

    assertThat(answer).isEqualTo("已说明");
    assertThat(executions).isEmpty();
    assertThat(model.requests.get(1).messages())
        .filteredOn(ToolResultMessage.class::isInstance)
        .extracting(ToolResultMessage.class::cast)
        .extracting(ToolResultMessage::status)
        .containsExactly(ToolResultStatus.SUCCESS, ToolResultStatus.ERROR);
  }

  private static ToolLoop loop(ToolCatalog catalog, ChatModelPort model) {
    ToolRuntimeSettings settings = ToolRuntimeSettings.readOnlyDefaults();
    ToolRegistry registry = new ToolRegistry(catalog, settings);
    LifecyclePublisher lifecycle = new LifecyclePublisher(TurnLifecycleObserver.noop());
    SideEffectBatchCoordinator coordinator =
        new SideEffectBatchCoordinator(
            registry,
            request -> ApprovalDecision.deniedFor(request, CLOCK.instant(), "deny-all"),
            ToolExecutionPolicy.registeredRisk(),
            CLOCK,
            Duration.ofMinutes(5),
            new SecureIdGenerator(),
            SideEffectLedger.unavailable(),
            lifecycle);
    return new ToolLoop(model, registry, lifecycle, 4, settings, coordinator);
  }

  private static ToolCatalog catalog(List<String> executions) {
    Tool currentTime = tool("current_time", "返回当前时间", executions, false);
    Tool calendar = tool("calendar_lookup", "查询日历事件", executions, true);
    return new ToolCatalog(
        List.of(
            new ToolCatalogEntry(
                currentTime,
                ToolCatalogVisibility.ALWAYS_ON,
                ToolCatalogSource.BUILTIN,
                "",
                List.of()),
            new ToolCatalogEntry(
                calendar,
                ToolCatalogVisibility.DEFERRED,
                ToolCatalogSource.MCP,
                "calendar",
                List.of("日历"))));
  }

  private static Tool tool(
      String name, String description, List<String> executions, boolean recordExecution) {
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
        if (recordExecution) {
          executions.add(name);
        }
        return ToolResult.success("ok");
      }
    };
  }

  private static List<String> names(List<ToolDefinition> definitions) {
    return definitions.stream().map(ToolDefinition::name).toList();
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
