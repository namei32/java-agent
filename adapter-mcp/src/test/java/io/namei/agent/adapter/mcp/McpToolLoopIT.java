package io.namei.agent.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.adapter.sqlite.JdbcSessionRepository;
import io.namei.agent.adapter.sqlite.SqliteSchemaInitializer;
import io.namei.agent.application.ChatCommand;
import io.namei.agent.application.ChatService;
import io.namei.agent.application.SessionExecutionGate;
import io.namei.agent.application.ToolCatalog;
import io.namei.agent.application.ToolCatalogEntry;
import io.namei.agent.application.ToolCatalogSource;
import io.namei.agent.application.ToolCatalogVisibility;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.ToolResultMessage;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.TurnLifecycleObserver;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpToolLoopIT {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-15T08:00:00Z"), ZoneOffset.UTC);

  @TempDir Path temp;

  @Test
  void executesMcpToolThroughExistingLoopAndPersistsOnlyTheFinalConversationTurn()
      throws Exception {
    McpSettings settings = McpReferenceServerSupport.settings(temp, 1_048_576, 1);
    McpRuntime runtime =
        McpRuntimes.staticReadOnly(
            new McpConfiguration(1, List.of(McpReferenceServerSupport.server(temp, "normal"))),
            settings);
    try {
      assertThat(runtime.status().state()).isEqualTo(McpRuntimeStatus.State.READY);
      assertThat(runtime.tools())
          .allSatisfy(tool -> assertThat(tool.definition().risk()).isEqualTo(ToolRisk.READ_ONLY));
      assertThat(runtime.tools())
          .extracting(tool -> tool.definition().name())
          .contains("mcp_docs__echo");

      Path database = temp.resolve("mcp-tool-loop.db");
      SqliteSchemaInitializer schema = new SqliteSchemaInitializer(database, 2_000);
      schema.initialize();
      JdbcSessionRepository repository = new JdbcSessionRepository(schema);
      ScriptedModel model = new ScriptedModel();
      ChatService chat =
          new ChatService(
              repository,
              model,
              new ConversationHistorySelector(),
              new HistoryLimits(40, 100_000),
              directGate(),
              "系统提示",
              CLOCK,
              new ToolCatalog(
                  runtime.tools().stream()
                      .map(
                          tool ->
                              new ToolCatalogEntry(
                                  tool,
                                  ToolCatalogVisibility.DEFERRED,
                                  ToolCatalogSource.MCP,
                                  "docs",
                                  tool.definition().name().endsWith("__echo")
                                      ? List.of("echo")
                                      : List.of()))
                      .toList()),
              4,
              TurnLifecycleObserver.noop());

      assertThat(chat.chat(new ChatCommand("mcp-session", "调用只读 MCP 工具")).assistant().content())
          .isEqualTo("MCP 闭环完成");

      assertThat(model.requests).hasSize(3);
      assertThat(model.requests.getFirst().tools())
          .extracting(definition -> definition.name())
          .containsExactly("tool_search");
      assertThat(model.requests.get(1).tools())
          .extracting(definition -> definition.name())
          .containsExactly("tool_search", "mcp_docs__echo");
      assertThat(model.requests.get(2).messages())
          .filteredOn(ToolResultMessage.class::isInstance)
          .extracting(message -> ((ToolResultMessage) message).content())
          .contains("来自 Java MCP");

      var persisted = repository.load("mcp-session");
      assertThat(persisted.messages())
          .extracting(message -> message.role(), message -> message.content())
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple(MessageRole.USER, "调用只读 MCP 工具"),
              org.assertj.core.groups.Tuple.tuple(MessageRole.ASSISTANT, "MCP 闭环完成"));
      try (var connection = schema.openConnection();
          var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT COUNT(*), COUNT(tool_chain) FROM messages")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(2);
        assertThat(rows.getInt(2)).isZero();
      }
    } finally {
      runtime.close();
    }
    assertThat(runtime.status().state()).isEqualTo(McpRuntimeStatus.State.CLOSED);
  }

  private static SessionExecutionGate directGate() {
    return new SessionExecutionGate() {
      @Override
      public <T> T execute(String sessionId, Supplier<T> action) {
        return action.get();
      }
    };
  }

  private static final class ScriptedModel implements ChatModelPort {
    private final List<ChatModelRequest> requests = new ArrayList<>();

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
      requests.add(request);
      if (requests.size() == 1) {
        return new ChatModelResponse(
            "", List.of(new ToolCall("mcp-search-1", "tool_search", Map.of("query", "echo"))));
      }
      if (requests.size() == 2) {
        return new ChatModelResponse(
            "",
            List.of(new ToolCall("mcp-call-1", "mcp_docs__echo", Map.of("text", "来自 Java MCP"))));
      }
      return new ChatModelResponse("MCP 闭环完成");
    }
  }
}
