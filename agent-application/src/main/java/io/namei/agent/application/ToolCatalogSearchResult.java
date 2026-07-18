package io.namei.agent.application;

import java.util.List;
import java.util.Objects;

record ToolCatalogSearchResult(
    List<ToolCatalogMatch> matched, List<String> unlocked, List<String> alreadyLoaded) {
  ToolCatalogSearchResult {
    matched = List.copyOf(Objects.requireNonNull(matched, "matched"));
    unlocked = List.copyOf(Objects.requireNonNull(unlocked, "unlocked"));
    alreadyLoaded = List.copyOf(Objects.requireNonNull(alreadyLoaded, "alreadyLoaded"));
  }
}
