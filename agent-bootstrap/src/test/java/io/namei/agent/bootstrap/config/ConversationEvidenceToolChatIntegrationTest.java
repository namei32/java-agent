package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.adapter.mcp.McpRuntimes;
import io.namei.agent.adapter.workspace.WorkspaceReadOnlyToolset;
import io.namei.agent.application.ChatCommand;
import io.namei.agent.application.ConversationEvidenceContextFactory;
import io.namei.agent.application.ConversationEvidenceToolset;
import io.namei.agent.application.ToolRuntimeMode;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.ToolResultMessage;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.MemoryRetrievalPort;
import io.namei.agent.kernel.skill.SkillCatalogPort;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;

class ConversationEvidenceToolChatIntegrationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void completesSearchThenFetchAgainstOnlyTheCurrentPersistedSession() throws Exception {
    var configuration = new ApplicationConfiguration();
    AgentProperties properties = agentProperties();
    var schema = configuration.sqliteSchema(properties);
    var jdbc = configuration.jdbcSessionRepository(schema);
    jdbc.appendTurn(
        "private-session",
        new PersistedTurn(
            new ChatMessage(MessageRole.USER, "cache fact"),
            OffsetDateTime.parse("2026-07-19T00:00:00Z"),
            new ChatMessage(MessageRole.ASSISTANT, "historical answer"),
            OffsetDateTime.parse("2026-07-19T00:00:01Z")));
    var requests = new ArrayList<ChatModelRequest>();
    ChatModelPort model =
        request -> {
          requests.add(request);
          return switch (requests.size()) {
            case 1 ->
                new ChatModelResponse(
                    "",
                    List.of(
                        new ToolCall(
                            "search-tools",
                            "tool_search",
                            Map.of("query", "select:search_messages,fetch_messages"))));
            case 2 ->
                new ChatModelResponse(
                    "",
                    List.of(
                        new ToolCall(
                            "search-history", "search_messages", Map.of("query", "historical"))));
            case 3 ->
                new ChatModelResponse(
                    "",
                    List.of(
                        new ToolCall(
                            "fetch-history",
                            "fetch_messages",
                            Map.of("ids", List.of("msg-v1:1")))));
            case 4 -> new ChatModelResponse("已根据历史原文回答");
            default -> throw new AssertionError("意外的模型调用");
          };
        };
    var evidenceProperties = new ConversationEvidenceProperties("CURRENT_SESSION_READ_ONLY");
    var evidencePort =
        configuration.conversationEvidencePort(properties, evidenceProperties, schema);
    ConversationEvidenceToolset tools =
        configuration.conversationEvidenceToolset(properties, evidenceProperties);
    ConversationEvidenceContextFactory contexts =
        configuration.conversationEvidenceContextFactory(
            properties, evidenceProperties, evidencePort);
    var memory =
        configuration.memoryContextService(
            configuration.memoryProfilePort(properties),
            MemoryRetrievalPort.disabled(),
            properties,
            new PromptProperties("MINIMAL", "UTC", 100_000, 100_000, 200_000, 9));
    var useCase =
        configuration.chatUseCase(
            configuration.sessionRepository(jdbc),
            model,
            configuration.sessionExecutionGate(properties),
            configuration.turnLifecycleObserver(),
            configuration.approvalPort(),
            memory,
            McpRuntimes.disabled(),
            WorkspaceReadOnlyToolset.disabled(),
            SkillCatalogPort.disabled(),
            new SkillProperties("DISABLED", "", 64, 65_536, 32_768, 32_768),
            tools,
            contexts,
            properties,
            "test-model",
            "",
            new ByteArrayResource("系统提示".getBytes(StandardCharsets.UTF_8)));

    assertThat(useCase.chat(new ChatCommand("private-session", "请找缓存事实")).assistant().content())
        .isEqualTo("已根据历史原文回答");
    assertThat(requests)
        .extracting(request -> names(request.tools()))
        .containsExactly(
            List.of("current_time", "tool_search"),
            List.of("current_time", "tool_search", "search_messages", "fetch_messages"),
            List.of("current_time", "tool_search", "search_messages", "fetch_messages"),
            List.of("current_time", "tool_search", "search_messages", "fetch_messages"));
    assertThat(toolContent(requests.get(2), "search_messages"))
        .contains("msg-v1:1")
        .doesNotContain("private-session")
        .doesNotContain("session_key");
    assertThat(toolContent(requests.get(3), "fetch_messages"))
        .contains("historical answer")
        .doesNotContain("private-session")
        .doesNotContain("tool_chain");
    assertThat(jdbc.load("private-session").messages()).hasSize(4);
  }

  private AgentProperties agentProperties() {
    return new AgentProperties(
        temporaryDirectory.resolve("agent-workspace"),
        null,
        new AgentProperties.Model(Duration.ofSeconds(5)),
        new AgentProperties.ToolLoop(6),
        new AgentProperties.Tools(
            ToolRuntimeMode.READ_ONLY, 8, 16, Duration.ofSeconds(1), 32, 16_384, 20_000),
        null);
  }

  private static List<String> names(List<ToolDefinition> definitions) {
    return definitions.stream().map(ToolDefinition::name).toList();
  }

  private static String toolContent(ChatModelRequest request, String toolName) {
    return request.messages().stream()
        .filter(ToolResultMessage.class::isInstance)
        .map(ToolResultMessage.class::cast)
        .filter(message -> message.toolName().equals(toolName))
        .map(ToolResultMessage::content)
        .findFirst()
        .orElseThrow();
  }
}
