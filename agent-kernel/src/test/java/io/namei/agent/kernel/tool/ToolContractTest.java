package io.namei.agent.kernel.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.lifecycle.TurnEventType;
import io.namei.agent.kernel.lifecycle.TurnLifecycleEvent;
import io.namei.agent.kernel.model.AssistantToolCallMessage;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.ToolResultMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolContractTest {
  @Test
  void definesReadOnlyToolsWithImmutableJsonSchema() {
    var properties = new LinkedHashMap<String, Object>();
    properties.put("query", Map.of("type", "string"));
    var schema = new LinkedHashMap<String, Object>();
    schema.put("type", "object");
    schema.put("properties", properties);

    var definition = new ToolDefinition("golden_lookup", "查询固定数据", schema, ToolRisk.READ_ONLY);
    properties.put("later", Map.of("type", "boolean"));

    assertThat(definition.inputSchema().toString()).doesNotContain("later");
    assertThatThrownBy(() -> definition.inputSchema().put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> new ToolDefinition("bad name", "说明", schema, ToolRisk.READ_ONLY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ToolDefinition("write", "写入", Map.of("type", "object"), ToolRisk.WRITE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("READ_ONLY");
  }

  @Test
  void representsAssistantToolCallsAndCorrelatedToolResults() {
    var nested = new ArrayList<>(List.of("配置"));
    var arguments = new LinkedHashMap<String, Object>();
    arguments.put("queries", nested);
    var call = new ToolCall("call-001", "golden_lookup", arguments);
    nested.add("后加值");

    var assistant = new AssistantToolCallMessage("正在查询", List.of(call));
    var result = new ToolResultMessage(call, ToolResult.success("固定结果"));

    assertThat(assistant.role()).isEqualTo(MessageRole.ASSISTANT);
    assertThat(assistant.toolCalls()).containsExactly(call);
    assertThat(call.arguments().toString()).doesNotContain("后加值");
    assertThat(result.role()).isEqualTo(MessageRole.TOOL);
    assertThat(result.toolCallId()).isEqualTo("call-001");
    assertThat(result.toolName()).isEqualTo("golden_lookup");
    assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
    assertThat(result.content()).isEqualTo("固定结果");
  }

  @Test
  void carriesToolDefinitionsAndCallsWithoutBreakingTextConvenienceConstructors() {
    var definition =
        new ToolDefinition("lookup", "查询", Map.of("type", "object"), ToolRisk.READ_ONLY);
    var call = new ToolCall("call-1", "lookup", Map.of());
    var request =
        new ChatModelRequest(List.of(new ChatMessage(MessageRole.USER, "问题")), List.of(definition));
    var toolResponse = new ChatModelResponse("", List.of(call));
    var textResponse = new ChatModelResponse("最终回答");

    assertThat(request.messages()).hasSize(1);
    assertThat(request.tools()).containsExactly(definition);
    assertThat(toolResponse.hasToolCalls()).isTrue();
    assertThat(textResponse.hasToolCalls()).isFalse();
    assertThat(textResponse.content()).isEqualTo("最终回答");
    assertThatThrownBy(
            () ->
                new ChatModelResponse("", List.of(call, new ToolCall("call-1", "other", Map.of()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("重复");
    assertThatThrownBy(() -> new ChatModelResponse("", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void lifecycleEventsExposeOnlyStableSafeFields() {
    var started = TurnLifecycleEvent.toolStarted(2, "call-9", "lookup");
    var completed = TurnLifecycleEvent.toolCompleted(2, "call-9", "lookup", ToolResultStatus.ERROR);

    assertThat(started.type()).isEqualTo(TurnEventType.TOOL_CALL_STARTED);
    assertThat(started.iteration()).isEqualTo(2);
    assertThat(started.callId()).isEqualTo("call-9");
    assertThat(completed.status()).isEqualTo("ERROR");
    assertThat(started.toString())
        .doesNotContain("arguments", "content", "result", "exception", "secret");
  }
}
