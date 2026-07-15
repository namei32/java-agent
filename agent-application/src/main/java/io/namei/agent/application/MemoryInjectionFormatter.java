package io.namei.agent.application;

import io.namei.agent.kernel.memory.MemoryItem;
import io.namei.agent.kernel.memory.MemorySearchHit;
import io.namei.agent.kernel.memory.MemoryType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class MemoryInjectionFormatter {
  private static final String RULES_HEADER = "## 【偏好与规则】候选记忆";
  private static final String RELATED_HEADER = "## 【相关信息】候选记忆";

  public MemoryInjection format(
      List<MemorySearchHit> hits, int maxRules, int maxRelated, int maxCharacters) {
    if (maxRules < 0 || maxRelated < 0 || maxCharacters < 1) {
      throw new IllegalArgumentException("Memory Injection 预算无效");
    }
    List<MemorySearchHit> snapshot = List.copyOf(Objects.requireNonNull(hits, "hits"));
    var rules = new ArrayList<MemoryItem>();
    var related = new ArrayList<MemoryItem>();
    for (MemorySearchHit hit : snapshot) {
      Objects.requireNonNull(hit, "hit");
      if (isRule(hit.item().type())) {
        rules.add(hit.item());
      } else {
        related.add(hit.item());
      }
    }

    var block = new StringBuilder(Math.min(maxCharacters, 1024));
    Section rulesSection = section(RULES_HEADER, rules, maxRules, maxCharacters, false);
    append(block, rulesSection);
    int remaining = maxCharacters - block.length() - (block.isEmpty() ? 0 : 2);
    Section relatedSection =
        remaining > 0
            ? section(RELATED_HEADER, related, maxRelated, remaining, true)
            : Section.empty();
    append(block, relatedSection);
    return new MemoryInjection(block.toString(), rulesSection.count(), relatedSection.count());
  }

  private static Section section(
      String header,
      List<MemoryItem> items,
      int limit,
      int maxCharacters,
      boolean includeHappenedAt) {
    if (limit == 0 || maxCharacters <= header.length()) {
      return Section.empty();
    }
    var section = new StringBuilder(header);
    int count = 0;
    for (MemoryItem item : items) {
      if (count == limit) {
        break;
      }
      String line = line(item, includeHappenedAt);
      if (section.length() + 1 + line.length() <= maxCharacters) {
        section.append('\n').append(line);
        count++;
      }
    }
    return count == 0 ? Section.empty() : new Section(section.toString(), count);
  }

  private static String line(MemoryItem item, boolean includeHappenedAt) {
    var line = new StringBuilder("- [").append(item.id()).append("] ");
    if (includeHappenedAt && item.happenedAt() != null) {
      line.append('[').append(item.happenedAt()).append("] ");
    }
    return line.append(MemoryManagementRules.content(item.content())).toString();
  }

  private static boolean isRule(MemoryType type) {
    return type == MemoryType.PREFERENCE || type == MemoryType.PROCEDURE;
  }

  private static void append(StringBuilder block, Section section) {
    if (section.count() == 0) {
      return;
    }
    if (!block.isEmpty()) {
      block.append("\n\n");
    }
    block.append(section.text());
  }

  private record Section(String text, int count) {
    private static Section empty() {
      return new Section("", 0);
    }
  }
}
