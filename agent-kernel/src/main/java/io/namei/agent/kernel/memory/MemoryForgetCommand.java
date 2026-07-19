package io.namei.agent.kernel.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Canonical, scope-bound batch request for a previously approved memory forget operation. */
public record MemoryForgetCommand(
    MemoryScope scope,
    String operationKey,
    List<String> requestedIds,
    String argumentHash,
    Instant requestedAt) {
  public MemoryForgetCommand {
    Objects.requireNonNull(scope, "scope");
    operationKey = MemoryValueRules.requestId(operationKey);
    requestedIds = canonicalIds(requestedIds);
    argumentHash = MemoryValueRules.sha256(argumentHash, "Argument Hash");
    Objects.requireNonNull(requestedAt, "requestedAt");
  }

  /** Applies the approved Java/Python-compatible blank removal and stable deduplication rules. */
  public static List<String> normalizeIds(List<String> rawIds) {
    Objects.requireNonNull(rawIds, "rawIds");
    var normalized = new LinkedHashSet<String>();
    for (String rawId : rawIds) {
      String id = Objects.requireNonNull(rawId, "Memory Item ID").strip();
      if (!id.isBlank()) {
        normalized.add(id);
      }
    }
    return List.copyOf(normalized);
  }

  @Override
  public String toString() {
    return "MemoryForgetCommand[requestedCount=" + requestedIds.size() + "]";
  }

  private static List<String> canonicalIds(List<String> values) {
    Objects.requireNonNull(values, "requestedIds");
    var supplied = List.copyOf(new ArrayList<>(values));
    if (supplied.isEmpty()) {
      throw new IllegalArgumentException("Forget 请求至少需要一个 Memory Item ID");
    }
    if (!supplied.equals(normalizeIds(supplied))) {
      throw new IllegalArgumentException("Forget 请求 ID 必须已完成规范化和稳定去重");
    }
    return supplied;
  }
}
