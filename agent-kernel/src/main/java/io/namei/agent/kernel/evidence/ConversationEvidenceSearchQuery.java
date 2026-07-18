package io.namei.agent.kernel.evidence;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded, already-normalized search request for one bound conversation session. */
public record ConversationEvidenceSearchQuery(
    List<String> terms, Optional<ConversationEvidenceRole> role, int limit, int offset) {
  public ConversationEvidenceSearchQuery {
    terms = List.copyOf(Objects.requireNonNull(terms, "terms"));
    role = Objects.requireNonNull(role, "role");
    if (terms.isEmpty()
        || terms.stream().anyMatch(term -> term == null || term.isBlank())
        || new LinkedHashSet<>(terms).size() != terms.size()) {
      throw new IllegalArgumentException("会话证据搜索词无效");
    }
    if (limit < 1 || limit > 50 || offset < 0 || offset > 1_000) {
      throw new IllegalArgumentException("会话证据搜索分页无效");
    }
  }
}
