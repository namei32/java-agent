package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.adapter.mcp.McpRuntimes;
import io.namei.agent.application.ChatCommand;
import io.namei.agent.application.ToolRuntimeMode;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.ToolResultMessage;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.MemoryRetrievalPort;
import io.namei.agent.kernel.port.SessionRepository;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;

class WorkspaceToolChatIntegrationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void exposesReadFileOnlyAfterToolSearchAndReturnsItsSafeProjectionToTheModel() throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve("tool-root"));
    Files.writeString(root.resolve("note.txt"), "source");
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
                            "search", "tool_search", Map.of("query", "select:read_file"))));
            case 2 ->
                new ChatModelResponse(
                    "", List.of(new ToolCall("read", "read_file", Map.of("path", "note.txt"))));
            case 3 -> new ChatModelResponse("已读取");
            default -> throw new AssertionError("意外的模型调用");
          };
        };

    var fixture = fixture(root, model);
    var result = fixture.useCase().chat(new ChatCommand("workspace", "读取 note"));

    assertThat(result.assistant().content()).isEqualTo("已读取");
    assertThat(requests)
        .extracting(request -> names(request.tools()))
        .containsExactly(
            List.of("current_time", "tool_search"),
            List.of("current_time", "tool_search", "read_file"),
            List.of("current_time", "tool_search", "read_file"));
    assertThat(requests.get(2).messages())
        .filteredOn(ToolResultMessage.class::isInstance)
        .extracting(ToolResultMessage.class::cast)
        .filteredOn(message -> message.toolName().equals("read_file"))
        .extracting(ToolResultMessage::content)
        .containsExactly("1: source");
    assertThat(fixture.repository().load("workspace").messages()).hasSize(2);
  }

  @Test
  @Tag("failure")
  void failedTurnDoesNotPersistSearchVisibilityOrAPartialConversation() throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve("tool-root"));
    var failedRequests = new ArrayList<ChatModelRequest>();
    ChatModelPort failing =
        request -> {
          failedRequests.add(request);
          return switch (failedRequests.size()) {
            case 1 ->
                new ChatModelResponse(
                    "",
                    List.of(
                        new ToolCall(
                            "search", "tool_search", Map.of("query", "select:read_file"))));
            case 2 ->
                new ChatModelResponse(
                    "", List.of(new ToolCall("read", "read_file", Map.of("path", "missing.txt"))));
            default -> throw new IllegalStateException("controlled model failure");
          };
        };
    var failed = fixture(root, failing);

    assertThatThrownBy(() -> failed.useCase().chat(new ChatCommand("workspace", "读取不存在文件")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("controlled model failure");
    assertThat(failed.repository().load("workspace").messages()).isEmpty();
    assertThat(failedRequests.get(2).messages())
        .filteredOn(ToolResultMessage.class::isInstance)
        .extracting(ToolResultMessage.class::cast)
        .filteredOn(message -> message.toolName().equals("read_file"))
        .extracting(ToolResultMessage::content)
        .containsExactly("WORKSPACE_NOT_FOUND");

    var recoveredRequests = new ArrayList<ChatModelRequest>();
    var recovered =
        fixture(
            root,
            request -> {
              recoveredRequests.add(request);
              return new ChatModelResponse("重新开始");
            },
            failed.repository());
    assertThat(recovered.useCase().chat(new ChatCommand("workspace", "新请求")).assistant().content())
        .isEqualTo("重新开始");
    assertThat(names(recoveredRequests.getFirst().tools()))
        .containsExactly("current_time", "tool_search");
  }

  private Fixture fixture(Path root, ChatModelPort model) throws Exception {
    return fixture(root, model, null);
  }

  private Fixture fixture(Path root, ChatModelPort model, SessionRepository existing)
      throws Exception {
    var configuration = new ApplicationConfiguration();
    AgentProperties properties = agentProperties();
    SessionRepository repository = existing;
    if (repository == null) {
      repository =
          configuration.sessionRepository(
              configuration.jdbcSessionRepository(configuration.sqliteSchema(properties)));
    }
    var workspaceProperties =
        new WorkspaceToolProperties(
            "READ_ONLY", root.toString(), 1_000_000, 400, 10_000, 20_000, 256);
    var tools = configuration.workspaceReadOnlyToolset(properties, workspaceProperties);
    var memory =
        configuration.memoryContextService(
            configuration.memoryProfilePort(properties),
            MemoryRetrievalPort.disabled(),
            properties,
            new PromptProperties("MINIMAL", "UTC", 100_000, 100_000, 200_000, 9));
    return new Fixture(
        configuration.chatUseCase(
            repository,
            model,
            configuration.sessionExecutionGate(properties),
            configuration.turnLifecycleObserver(),
            configuration.approvalPort(),
            memory,
            McpRuntimes.disabled(),
            tools,
            properties,
            "test-model",
            "",
            new ByteArrayResource("系统提示".getBytes(StandardCharsets.UTF_8))),
        repository);
  }

  private AgentProperties agentProperties() {
    return new AgentProperties(
        temporaryDirectory.resolve("agent-workspace"),
        new AgentProperties.History(40, 100_000),
        new AgentProperties.Model(Duration.ofSeconds(5)),
        new AgentProperties.ToolLoop(4),
        new AgentProperties.Tools(
            ToolRuntimeMode.READ_ONLY, 8, 16, Duration.ofSeconds(1), 32, 16_384, 20_000),
        null);
  }

  private static List<String> names(List<ToolDefinition> definitions) {
    return definitions.stream().map(ToolDefinition::name).toList();
  }

  private record Fixture(
      io.namei.agent.application.ChatUseCase useCase, SessionRepository repository) {}
}
