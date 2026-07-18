package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SkillTemplateConfigurationTest {
  @Test
  void templatesKeepSkillsDisabledAndExposeOnlyReadOnlyBudgets() throws Exception {
    String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
    String environment = Files.readString(Path.of("../.env.example"));

    assertThat(yaml)
        .contains("mode: ${AGENT_SKILLS_MODE:DISABLED}")
        .contains("builtin-root: ${AGENT_SKILLS_BUILTIN_ROOT:}")
        .contains("max-skills: ${AGENT_SKILLS_MAX_SKILLS:64}")
        .contains("max-file-bytes: ${AGENT_SKILLS_MAX_FILE_BYTES:65536}")
        .contains("max-catalog-code-points: ${AGENT_SKILLS_MAX_CATALOG_CODE_POINTS:32768}")
        .contains("max-active-code-points: ${AGENT_SKILLS_MAX_ACTIVE_CODE_POINTS:32768}");
    assertThat(environment)
        .contains("AGENT_SKILLS_MODE=DISABLED")
        .contains("AGENT_SKILLS_BUILTIN_ROOT=")
        .contains("AGENT_SKILLS_MAX_SKILLS=64")
        .contains("AGENT_SKILLS_MAX_FILE_BYTES=65536")
        .contains("AGENT_SKILLS_MAX_CATALOG_CODE_POINTS=32768")
        .contains("AGENT_SKILLS_MAX_ACTIVE_CODE_POINTS=32768");
  }
}
