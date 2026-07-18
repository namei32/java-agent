package io.namei.agent.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class ToolCatalogSession {
  private final ToolCatalog catalog;
  private final LinkedHashSet<String> visibleNames;

  ToolCatalogSession(ToolCatalog catalog, List<String> initiallyVisible) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.visibleNames =
        new LinkedHashSet<>(Objects.requireNonNull(initiallyVisible, "initiallyVisible"));
  }

  Set<String> visibleNames() {
    return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(visibleNames));
  }

  List<String> visibleNamesInOrder() {
    return List.copyOf(visibleNames);
  }

  boolean isVisible(String toolName) {
    return visibleNames.contains(toolName);
  }

  boolean unlock(ToolCatalog catalog, String toolName) {
    if (this.catalog != catalog) {
      throw new IllegalArgumentException("Tool Catalog Session 不属于当前 Catalog");
    }
    if (!catalog.isDeferred(toolName)) {
      return false;
    }
    return visibleNames.add(toolName);
  }

  boolean isOwnedBy(ToolCatalog catalog) {
    return this.catalog == catalog;
  }
}
