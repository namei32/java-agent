package io.namei.agent.kernel.skill;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 由一次一致性读取产生的不可变 Catalog 和已选 Always 内容。 */
public record SkillCatalogSnapshot(
    List<SkillDescriptor> descriptors, List<SkillContent> activeContents) {
  public SkillCatalogSnapshot {
    descriptors = orderedDescriptors(descriptors);
    activeContents = orderedActiveContents(activeContents, descriptors);
  }

  public static SkillCatalogSnapshot empty() {
    return new SkillCatalogSnapshot(List.of(), List.of());
  }

  private static List<SkillDescriptor> orderedDescriptors(List<SkillDescriptor> values) {
    if (values == null) {
      throw SkillContract.violation();
    }
    Map<String, SkillDescriptor> byName = new HashMap<>();
    for (SkillDescriptor descriptor : values) {
      SkillDescriptor candidate = Objects.requireNonNull(descriptor, "descriptor");
      if (byName.putIfAbsent(candidate.name(), candidate) != null) {
        throw SkillContract.violation();
      }
    }
    return byName.values().stream().sorted(Comparator.comparing(SkillDescriptor::name)).toList();
  }

  private static List<SkillContent> orderedActiveContents(
      List<SkillContent> values, List<SkillDescriptor> descriptors) {
    if (values == null) {
      throw SkillContract.violation();
    }
    Map<String, SkillDescriptor> byName =
        descriptors.stream()
            .collect(java.util.stream.Collectors.toMap(SkillDescriptor::name, value -> value));
    Set<String> names = new HashSet<>();
    for (SkillContent content : values) {
      SkillContent candidate = Objects.requireNonNull(content, "active content");
      SkillDescriptor descriptor = byName.get(candidate.name());
      if (descriptor == null
          || !descriptor.available()
          || !descriptor.always()
          || !names.add(candidate.name())) {
        throw SkillContract.violation();
      }
    }
    return values.stream().sorted(Comparator.comparing(SkillContent::name)).toList();
  }
}
