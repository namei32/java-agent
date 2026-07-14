package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.adapter.workspace.MarkdownMemoryProfileAdapter;
import io.namei.agent.kernel.memory.MemoryProfile;
import io.namei.agent.kernel.memory.MemoryRetrievalRequest;
import io.namei.agent.kernel.memory.MemoryRetrievalStatus;
import io.namei.agent.kernel.memory.MemoryRuntimeMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryConfigurationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void appliesDisabledProductionDefaultsAndRejectsInvalidLimits() {
    var defaults =
        new AgentProperties(temporaryDirectory.resolve("workspace"), null, null, null, null, null);

    assertThat(defaults.memory().mode()).isEqualTo(MemoryRuntimeMode.DISABLED);
    assertThat(defaults.memory().maxFileBytes()).isEqualTo(65_536);
    assertThat(defaults.memory().maxContextCharacters()).isEqualTo(100_000);
    assertThat(defaults.memory().maxRetrievedCharacters()).isEqualTo(20_000);
    assertThatThrownBy(
            () -> new AgentProperties.Memory(MemoryRuntimeMode.READ_ONLY, 0, 100_000, 20_000))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("agent.memory");
    assertThatThrownBy(
            () -> new AgentProperties.Memory(MemoryRuntimeMode.READ_ONLY, 65_536, 0, 20_000))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("agent.memory");
    assertThatThrownBy(
            () -> new AgentProperties.Memory(MemoryRuntimeMode.READ_ONLY, 65_536, 100_000, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("agent.memory");
  }

  @Test
  void disabledModeReturnsEmptyProfileWithoutTouchingWorkspace() {
    Path workspace = temporaryDirectory.resolve("must-not-be-read-or-created");
    var properties = properties(workspace, MemoryRuntimeMode.DISABLED);
    var configuration = new ApplicationConfiguration();

    var profiles = configuration.memoryProfilePort(properties);

    assertThat(profiles.load()).isEqualTo(MemoryProfile.empty());
    assertThat(workspace).doesNotExist();
    assertThat(profiles).isNotInstanceOf(MarkdownMemoryProfileAdapter.class);
  }

  @Test
  void readOnlyModeWiresMarkdownProfileButKeepsRetrievalDisabled() throws Exception {
    Path workspace = temporaryDirectory.resolve("read-only-workspace");
    Path memory = Files.createDirectories(workspace.resolve("memory"));
    Files.writeString(memory.resolve("SELF.md"), "只读身份");
    var properties = properties(workspace, MemoryRuntimeMode.READ_ONLY);
    var configuration = new ApplicationConfiguration();

    var profiles = configuration.memoryProfilePort(properties);
    var retrieval = configuration.memoryRetrievalPort();

    assertThat(profiles).isInstanceOf(MarkdownMemoryProfileAdapter.class);
    assertThat(profiles.load().selfModel()).isEqualTo("只读身份");
    assertThat(
            retrieval
                .retrieve(
                    new MemoryRetrievalRequest(
                        "a".repeat(64), "问题", List.of(), Instant.parse("2026-07-14T00:00:00Z")))
                .trace()
                .status())
        .isEqualTo(MemoryRetrievalStatus.DISABLED);
    assertThat(configuration.memoryContextService(profiles, retrieval, properties)).isNotNull();
  }

  @Test
  void templatesKeepMemoryExplicitlyDisabledWithApprovedLimits() throws Exception {
    String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
    String environmentTemplate = Files.readString(Path.of("../.env.example"));

    assertThat(yaml)
        .contains("mode: ${AGENT_MEMORY_MODE:DISABLED}")
        .contains("max-file-bytes: ${AGENT_MEMORY_MAX_FILE_BYTES:65536}")
        .contains("max-context-characters: ${AGENT_MEMORY_MAX_CONTEXT_CHARACTERS:100000}")
        .contains("max-retrieved-characters: ${AGENT_MEMORY_MAX_RETRIEVED_CHARACTERS:20000}");
    assertThat(environmentTemplate).contains("AGENT_MEMORY_MODE=DISABLED");
  }

  private static AgentProperties properties(Path workspace, MemoryRuntimeMode mode) {
    return new AgentProperties(
        workspace,
        null,
        null,
        null,
        null,
        new AgentProperties.Memory(mode, 65_536, 100_000, 20_000));
  }
}
