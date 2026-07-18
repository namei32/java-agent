package io.namei.agent.application;

import io.namei.agent.kernel.skill.SkillCatalogPort;
import io.namei.agent.kernel.skill.SkillCatalogSnapshot;
import io.namei.agent.kernel.skill.SkillContent;
import io.namei.agent.kernel.skill.SkillDescriptor;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Renders only the safe, read-only Skill projections admitted by the R12-S1 contract. */
public final class SkillPromptService {
  private final SkillCatalogPort catalog;
  private final int maxCatalogCodePoints;
  private final int maxActiveCodePoints;

  public SkillPromptService(
      SkillCatalogPort catalog, int maxCatalogCodePoints, int maxActiveCodePoints) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    if (maxCatalogCodePoints < 1 || maxActiveCodePoints < 1) {
      throw new IllegalArgumentException("Skill Prompt 预算必须大于零");
    }
    this.maxCatalogCodePoints = maxCatalogCodePoints;
    this.maxActiveCodePoints = maxActiveCodePoints;
  }

  public static SkillPromptService disabled() {
    return new SkillPromptService(SkillCatalogPort.disabled(), 1, 1);
  }

  public SkillPromptSections render() {
    SkillCatalogSnapshot snapshot = Objects.requireNonNull(catalog.snapshot(), "Skill snapshot");
    return new SkillPromptSections(
        renderCatalog(snapshot.descriptors()), renderActive(snapshot.activeContents()));
  }

  private String renderCatalog(List<SkillDescriptor> descriptors) {
    if (descriptors.isEmpty()) {
      return "";
    }
    String opening = "<skills>";
    String closing = "</skills>";
    StringBuilder result = new StringBuilder(opening);
    for (SkillDescriptor descriptor :
        descriptors.stream().sorted(Comparator.comparing(SkillDescriptor::name)).toList()) {
      String entry =
          "\n  <skill available=\""
              + descriptor.available()
              + "\" source=\""
              + descriptor.source().name().toLowerCase(java.util.Locale.ROOT)
              + "\">\n"
              + "    <name>"
              + escapeXml(descriptor.name())
              + "</name>\n"
              + "    <description>"
              + escapeXml(descriptor.description())
              + "</description>\n"
              + "  </skill>";
      if (codePoints(result.toString()) + codePoints(entry) + 1 + codePoints(closing)
          > maxCatalogCodePoints) {
        break;
      }
      result.append(entry);
    }
    if (result.toString().equals(opening)) {
      return "";
    }
    return result.append('\n').append(closing).toString();
  }

  private String renderActive(List<SkillContent> contents) {
    StringBuilder result = new StringBuilder();
    for (SkillContent content :
        contents.stream().sorted(Comparator.comparing(SkillContent::name)).toList()) {
      String block = "### Skill: " + content.name() + "\n\n" + stripFrontmatter(content.body());
      int remaining = maxActiveCodePoints - codePoints(result.toString());
      if (remaining <= 0) {
        break;
      }
      if (result.length() > 0) {
        String separator = "\n\n---\n\n";
        if (codePoints(separator) >= remaining) {
          break;
        }
        result.append(separator);
        remaining -= codePoints(separator);
      }
      result.append(firstCodePoints(block, remaining));
      if (codePoints(block) > remaining) {
        break;
      }
    }
    return result.toString();
  }

  private static String stripFrontmatter(String raw) {
    String normalized = raw.replace("\r\n", "\n");
    if (!normalized.startsWith("---\n")) {
      return normalized.strip();
    }
    int closing = normalized.indexOf("\n---\n", 4);
    return closing < 0
        ? normalized.strip()
        : normalized.substring(closing + "\n---\n".length()).strip();
  }

  private static String escapeXml(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String firstCodePoints(String value, int maximum) {
    if (maximum <= 0 || value.isEmpty()) {
      return "";
    }
    int available = value.codePointCount(0, value.length());
    if (available <= maximum) {
      return value;
    }
    return value.substring(0, value.offsetByCodePoints(0, maximum));
  }

  private static int codePoints(String value) {
    return value.codePointCount(0, value.length());
  }
}
