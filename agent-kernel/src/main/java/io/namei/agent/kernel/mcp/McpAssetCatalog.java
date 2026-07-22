package io.namei.agent.kernel.mcp;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 单个不可变、确定性排序且仅包含元数据的快照。 */
public record McpAssetCatalog(List<McpAssetDescriptor> descriptors) {
  public McpAssetCatalog {
    if (descriptors == null) {
      throw McpAssetContract.violation();
    }
    Set<String> localNames = new HashSet<>();
    for (McpAssetDescriptor descriptor : descriptors) {
      McpAssetDescriptor candidate = Objects.requireNonNull(descriptor, "descriptor");
      if (!localNames.add(candidate.localName())) {
        throw McpAssetContract.violation();
      }
    }
    descriptors =
        descriptors.stream()
            .sorted(
                Comparator.comparing(McpAssetDescriptor::serverId)
                    .thenComparing(McpAssetDescriptor::kind)
                    .thenComparing(McpAssetDescriptor::localName))
            .toList();
  }

  public static McpAssetCatalog empty() {
    return new McpAssetCatalog(List.of());
  }
}
