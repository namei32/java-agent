package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.skill.SkillCatalogPort;
import io.namei.agent.kernel.skill.SkillCatalogSnapshot;
import io.namei.agent.kernel.skill.SkillContent;
import io.namei.agent.kernel.skill.SkillDescriptor;
import io.namei.agent.kernel.skill.SkillSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillPromptServiceTest {
  @Test
  void rendersAStablePathFreeXmlCatalogAndAlwaysContent() {
    SkillCatalogPort catalog =
        () ->
            new SkillCatalogSnapshot(
                List.of(
                    new SkillDescriptor(
                        "zeta", "Use <xml> & safely", SkillSource.BUILTIN, true, false),
                    new SkillDescriptor(
                        "daily-rules", "Daily rules", SkillSource.WORKSPACE, true, true),
                    new SkillDescriptor(
                        "unavailable", "Missing secret", SkillSource.BUILTIN, false, true)),
                List.of(new SkillContent("daily-rules", "Use Chinese.\n")));

    SkillPromptSections sections = new SkillPromptService(catalog, 2_000, 2_000).render();

    assertThat(sections.catalog())
        .isEqualTo(
            """
            <skills>
              <skill available="true" source="workspace">
                <name>daily-rules</name>
                <description>Daily rules</description>
              </skill>
              <skill available="false" source="builtin">
                <name>unavailable</name>
                <description>Missing secret</description>
              </skill>
              <skill available="true" source="builtin">
                <name>zeta</name>
                <description>Use &lt;xml&gt; &amp; safely</description>
              </skill>
            </skills>""");
    assertThat(sections.active()).isEqualTo("### Skill: daily-rules\n\nUse Chinese.");
    assertThat(sections.catalog()).doesNotContain("/Users/", "SKILL.md", "PATH", "CALENDAR_TOKEN");
  }

  @Test
  void appliesDeterministicCodePointBudgetsWithoutInjectingFrontmatter() {
    SkillCatalogPort catalog =
        () ->
            new SkillCatalogSnapshot(
                List.of(
                    new SkillDescriptor(
                        "daily-rules", "Daily rules", SkillSource.BUILTIN, true, true)),
                List.of(
                    new SkillContent("daily-rules", "---\nname: daily-rules\n---\n中文规则😀中文规则😀")));

    SkillPromptSections sections = new SkillPromptService(catalog, 200, 24).render();

    assertThat(codePoints(sections.catalog())).isLessThanOrEqualTo(200);
    assertThat(codePoints(sections.active())).isLessThanOrEqualTo(24);
    assertThat(sections.active()).startsWith("### Skill: daily-rules\n\n");
    assertThat(sections.active()).doesNotContain("name: daily-rules", "---");
  }

  @Test
  void rejectsInvalidPromptBudgets() {
    assertThatThrownBy(() -> new SkillPromptService(SkillCatalogPort.disabled(), 0, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SkillPromptService(SkillCatalogPort.disabled(), 10, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static int codePoints(String value) {
    return value.codePointCount(0, value.length());
  }
}
