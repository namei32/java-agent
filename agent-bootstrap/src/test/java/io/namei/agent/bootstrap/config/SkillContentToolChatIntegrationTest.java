package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.adapter.mcp.McpRuntimes;
import io.namei.agent.adapter.workspace.WorkspaceReadOnlyToolset;
import io.namei.agent.application.ChatCommand;
import io.namei.agent.application.ToolRuntimeMode;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.ToolResultMessage;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.MemoryRetrievalPort;
import io.namei.agent.kernel.port.SessionRepository;
import io.namei.agent.kernel.skill.SkillCatalogPort;
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

class SkillContentToolChatIntegrationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void exposesReadSkillOnlyAfterSearchAndReturnsOnlyItsAuditedBodyToTheModel() throws Exception {
    Path skillsRoot = Files.createDirectory(temporaryDirectory.resolve("builtin-skills"));
    skill(skillsRoot, "daily-rules", "Daily rules", "Use Chinese.");
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
                            "search", "tool_search", Map.of("query", "select:read_skill"))));
            case 2 ->
                new ChatModelResponse(
                    "", List.of(new ToolCall("read", "read_skill", Map.of("name", "daily-rules"))));
            case 3 -> new ChatModelResponse("已按规则回答");
            default -> throw new AssertionError("意外的模型调用");
          };
        };

    var fixture = fixture(skillsRoot, model);
    var result = fixture.useCase().chat(new ChatCommand("skills", "遵循 daily-rules"));

    assertThat(result.assistant().content()).isEqualTo("已按规则回答");
    assertThat(requests)
        .extracting(request -> names(request.tools()))
        .containsExactly(
            List.of("current_time", "tool_search"),
            List.of("current_time", "tool_search", "read_skill"),
            List.of("current_time", "tool_search", "read_skill"));
    assertThat(requests.get(2).messages())
        .filteredOn(ToolResultMessage.class::isInstance)
        .extracting(ToolResultMessage.class::cast)
        .filteredOn(message -> message.toolName().equals("read_skill"))
        .extracting(ToolResultMessage::content)
        .containsExactly("Use Chinese.");
    assertThat(fixture.repository().load("skills").messages()).hasSize(2);
  }

  @Test
  @Tag("failure")
  void directReadSkillCallIsUnavailableUntilTheCurrentTurnSearches() throws Exception {
    Path skillsRoot = Files.createDirectory(temporaryDirectory.resolve("builtin-skills"));
    skill(skillsRoot, "daily-rules", "Daily rules", "Use Chinese.");
    var requests = new ArrayList<ChatModelRequest>();
    var fixture =
        fixture(
            skillsRoot,
            request -> {
              requests.add(request);
              return switch (requests.size()) {
                case 1 ->
                    new ChatModelResponse(
                        "",
                        List.of(new ToolCall("read", "read_skill", Map.of("name", "daily-rules"))));
                case 2 -> new ChatModelResponse("安全拒绝后继续");
                default -> throw new AssertionError("意外的模型调用");
              };
            });

    assertThat(fixture.useCase().chat(new ChatCommand("skills", "直接读取")).assistant().content())
        .isEqualTo("安全拒绝后继续");
    assertThat(requests.get(1).messages())
        .filteredOn(ToolResultMessage.class::isInstance)
        .extracting(ToolResultMessage.class::cast)
        .filteredOn(message -> message.toolName().equals("read_skill"))
        .extracting(ToolResultMessage::content)
        .containsExactly("工具不可用。");
  }

  private Fixture fixture(Path skillsRoot, ChatModelPort model) throws Exception {
    var configuration = new ApplicationConfiguration();
    AgentProperties properties = agentProperties();
    SessionRepository repository =
        configuration.sessionRepository(
            configuration.jdbcSessionRepository(configuration.sqliteSchema(properties)));
    var skills =
        new SkillProperties("READ_ONLY", skillsRoot.toString(), 64, 65_536, 32_768, 32_768, 20_000);
    SkillCatalogPort catalog = configuration.skillCatalogPort(properties, skills);
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
            WorkspaceReadOnlyToolset.disabled(),
            catalog,
            skills,
            properties,
            "test-model",
            "",
            new ByteArrayResource("系统提示".getBytes(StandardCharsets.UTF_8))),
        repository);
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

  private static void skill(Path root, String name, String description, String body)
      throws Exception {
    Path directory = Files.createDirectories(root.resolve(name));
    Files.writeString(
        directory.resolve("SKILL.md"),
        "---\nname: "
            + name
            + "\ndescription: "
            + description
            + "\nmetadata: {\"akashic\":{}}\n---\n\n"
            + body,
        StandardCharsets.UTF_8);
  }

  private record Fixture(
      io.namei.agent.application.ChatUseCase useCase, SessionRepository repository) {}
}
