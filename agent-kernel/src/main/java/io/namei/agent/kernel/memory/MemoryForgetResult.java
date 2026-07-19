package io.namei.agent.kernel.memory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Redacted, order-stable projection of one scope-bound batch forget operation. */
public record MemoryForgetResult(
    List<String> requestedIds, List<String> supersededIds, List<String> missingIds) {
  public MemoryForgetResult {
    requestedIds = canonicalIds(requestedIds, "requestedIds");
    supersededIds = canonicalIds(supersededIds, "supersededIds");
    missingIds = canonicalIds(missingIds, "missingIds");
    validatePartition(requestedIds, supersededIds, missingIds);
  }

  public static MemoryForgetResult empty() {
    return new MemoryForgetResult(List.of(), List.of(), List.of());
  }

  public int count() {
    return supersededIds.size();
  }

  /**
   * Contains only the approved public result fields; never MemoryItem or content-bearing values.
   */
  public Map<String, Object> safeProjection() {
    var projection = new LinkedHashMap<String, Object>();
    projection.put("requested_ids", requestedIds);
    projection.put("superseded_ids", supersededIds);
    projection.put("missing_ids", missingIds);
    projection.put("count", count());
    return Collections.unmodifiableMap(projection);
  }

  @Override
  public String toString() {
    return "MemoryForgetResult[requestedCount="
        + requestedIds.size()
        + ", supersededCount="
        + supersededIds.size()
        + ", missingCount="
        + missingIds.size()
        + "]";
  }

  private static List<String> canonicalIds(List<String> values, String field) {
    Objects.requireNonNull(values, field);
    var ids = List.copyOf(values);
    var unique = new LinkedHashSet<String>();
    for (String id : ids) {
      Objects.requireNonNull(id, field + " item");
      if (id.isBlank() || !id.equals(id.strip()) || !unique.add(id)) {
        throw new IllegalArgumentException(field + " 必须是已规范化的唯一 ID 序列");
      }
    }
    return ids;
  }

  private static void validatePartition(
      List<String> requestedIds, List<String> supersededIds, List<String> missingIds) {
    if (!isOrderedSubset(requestedIds, supersededIds)
        || !isOrderedSubset(requestedIds, missingIds)) {
      throw new IllegalArgumentException("Forget 结果必须保持请求 ID 的相对顺序");
    }
    var outcomes = new LinkedHashSet<String>(supersededIds);
    if (!Collections.disjoint(outcomes, missingIds)) {
      throw new IllegalArgumentException("Forget 结果 ID 不能同时命中和缺失");
    }
    outcomes.addAll(missingIds);
    if (!outcomes.equals(new LinkedHashSet<>(requestedIds))) {
      throw new IllegalArgumentException("Forget 结果必须覆盖每个请求 ID 一次");
    }
  }

  private static boolean isOrderedSubset(List<String> requestedIds, List<String> subset) {
    int requestedIndex = 0;
    for (String id : subset) {
      while (requestedIndex < requestedIds.size() && !requestedIds.get(requestedIndex).equals(id)) {
        requestedIndex++;
      }
      if (requestedIndex == requestedIds.size()) {
        return false;
      }
      requestedIndex++;
    }
    return true;
  }
}
