package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.adapter.mcp.McpRuntimes;
import io.namei.agent.application.ProactiveJobInspectionToolset;
import io.namei.agent.application.ToolRuntimeMode;
import io.namei.agent.bootstrap.plugin.PluginRuntime;
import io.namei.agent.bootstrap.proactive.ProactiveInspectionProperties;
import io.namei.agent.bootstrap.proactive.ProactiveProperties;
import io.namei.agent.bootstrap.proactive.ProactiveRuntime;
import io.namei.agent.kernel.skill.SkillCatalogPort;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ProactiveJobInspectionBootstrapConfigurationTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  @TempDir Path temporaryDirectory;

  @Test
  void globalDisabledAndInspectionDisabledRegisterNothingWithoutStartingRuntime() {
    var configuration = new ApplicationConfiguration();
    try (var inactive = inactiveRuntime()) {
      assertThat(
              configuration
                  .proactiveJobInspectionToolset(
                      agent(ToolRuntimeMode.DISABLED, 8_192),
                      new ProactiveInspectionProperties("ACTIVE_RUNTIME"),
                      inactive)
                  .tools())
          .isEmpty();
      assertThat(
              configuration
                  .proactiveJobInspectionToolset(
                      agent(ToolRuntimeMode.READ_ONLY, 8_192),
                      new ProactiveInspectionProperties("DISABLED"),
                      inactive)
                  .tools())
          .isEmpty();
    }
  }

  @Test
  void explicitReadOnlyActiveRuntimeRegistersOneDeferredBuiltin() {
    var configuration = new ApplicationConfiguration();
    AgentProperties agent = agent(ToolRuntimeMode.READ_ONLY, 8_192);
    try (var runtime = activeRuntime()) {
      ProactiveJobInspectionToolset tools =
          configuration.proactiveJobInspectionToolset(
              agent, new ProactiveInspectionProperties("ACTIVE_RUNTIME"), runtime);

      assertThat(tools.tools())
          .extracting(tool -> tool.definition().name())
          .containsExactly("list_local_proactive_jobs");
      assertThat(
              configuration
                  .configuredToolCatalog(
                      agent,
                      McpRuntimes.disabled(),
                      io.namei.agent.adapter.workspace.WorkspaceReadOnlyToolset.disabled(),
                      SkillCatalogPort.disabled(),
                      new SkillProperties("DISABLED", "", 64, 65_536, 32_768, 32_768),
                      io.namei.agent.application.ConversationEvidenceToolset.disabled(),
                      io.namei.agent.application.MemoryRecallToolset.disabled(),
                      tools)
                  .initialDefinitions())
          .extracting(definition -> definition.name())
          .containsExactly("current_time", "tool_search");
    }
  }

  @Test
  void unsafeModesInactiveRuntimeAndInsufficientBudgetFailClosed() {
    var configuration = new ApplicationConfiguration();
    var requested = new ProactiveInspectionProperties("ACTIVE_RUNTIME");
    try (var active = activeRuntime();
        var inactive = inactiveRuntime()) {
      assertThatThrownBy(
              () ->
                  configuration.proactiveJobInspectionToolset(
                      agent(ToolRuntimeMode.APPROVAL_REQUIRED, 8_192), requested, active))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("只读 Proactive Job Inspection Tool 要求 agent.tools.mode=READ_ONLY");
      assertThatThrownBy(
              () ->
                  configuration.proactiveJobInspectionToolset(
                      agent(ToolRuntimeMode.READ_ONLY, 8_192), requested, inactive))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("启用 Proactive Job Inspection Tool 需要活动的 LOCAL_SQLITE Runtime");
      assertThatThrownBy(
              () ->
                  configuration.proactiveJobInspectionToolset(
                      agent(ToolRuntimeMode.READ_ONLY, 8_191), requested, active))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("agent.proactive-inspection 单项预算不能大于 agent.tools.max-result-characters");
    }
  }

  @Test
  void springChatUseCaseBeanReceivesTheInspectionToolset() {
    var beanMethod =
        Arrays.stream(ApplicationConfiguration.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("chatUseCase"))
            .filter(method -> method.isAnnotationPresent(Bean.class))
            .findFirst()
            .orElseThrow();

    assertThat(beanMethod.getParameterTypes()).contains(ProactiveJobInspectionToolset.class);
  }

  @Test
  @Tag("compat")
  void consumesEveryRegistrationFixtureCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(
            goldenRoot().resolve("proactive/read-only-local-proactive-job-inspection-v1.json"));
    assertThat(fixture.path("cases")).hasSize(13);
    var configuration = new ApplicationConfiguration();
    for (JsonNode testCase : fixture.path("cases")) {
      if (!"registration".equals(testCase.path("group").asString())) {
        continue;
      }
      ToolRuntimeMode toolMode =
          ToolRuntimeMode.valueOf(testCase.path("input").path("toolMode").asString());
      try (var runtime = activeRuntime()) {
        ProactiveJobInspectionToolset tools =
            configuration.proactiveJobInspectionToolset(
                agent(toolMode, 8_192),
                new ProactiveInspectionProperties(
                    testCase.path("input").path("inspectionMode").asString()),
                runtime);
        if (!testCase.path("expected").path("registered").asBoolean()) {
          assertThat(tools.tools()).as(testCase.path("id").asString()).isEmpty();
          continue;
        }
        assertThat(tools.tools()).as(testCase.path("id").asString()).hasSize(1);
        assertThat(tools.tools().getFirst().definition().risk().name())
            .isEqualTo(testCase.path("expected").path("risk").asString());
        assertThat(
                configuration
                    .configuredToolCatalog(
                        agent(toolMode, 8_192),
                        McpRuntimes.disabled(),
                        io.namei.agent.adapter.workspace.WorkspaceReadOnlyToolset.disabled(),
                        SkillCatalogPort.disabled(),
                        new SkillProperties("DISABLED", "", 64, 65_536, 32_768, 32_768),
                        io.namei.agent.application.ConversationEvidenceToolset.disabled(),
                        io.namei.agent.application.MemoryRecallToolset.disabled(),
                        tools)
                    .initialDefinitions())
            .extracting(definition -> definition.name())
            .containsExactly("current_time", "tool_search");
      }
    }
  }

  private ProactiveRuntime inactiveRuntime() {
    return ProactiveRuntime.start(
        new ProactiveProperties("DISABLED", null, null, null, null, null, List.of()),
        temporaryDirectory.resolve("inactive"),
        PluginRuntime.disabled());
  }

  private ProactiveRuntime activeRuntime() {
    var plan =
        new ProactiveProperties.Plan(
            "daily-summary",
            "AT",
            Instant.parse("2030-07-19T00:00:00Z"),
            null,
            "a".repeat(64),
            "b".repeat(64),
            3);
    return ProactiveRuntime.start(
        new ProactiveProperties(
            "LOCAL_SQLITE",
            "proactive-local",
            Duration.ofSeconds(30),
            Duration.ofHours(1),
            Duration.ofSeconds(1),
            Duration.ofMinutes(5),
            List.of(plan)),
        temporaryDirectory.resolve("active"),
        PluginRuntime.disabled());
  }

  private AgentProperties agent(ToolRuntimeMode mode, int maxResultCharacters) {
    return new AgentProperties(
        temporaryDirectory.resolve("workspace-" + mode + "-" + maxResultCharacters),
        null,
        new AgentProperties.Model(Duration.ofSeconds(60)),
        null,
        new AgentProperties.Tools(
            mode, 8, 16, Duration.ofSeconds(5), 32, 16_384, maxResultCharacters),
        null);
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
