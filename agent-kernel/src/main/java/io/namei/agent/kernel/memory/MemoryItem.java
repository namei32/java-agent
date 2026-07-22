package io.namei.agent.kernel.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * Java 原生记忆系统中的一条完整持久化记忆。
 *
 * @param id 不透明记忆标识
 * @param scope 记忆可见范围
 * @param type 事实、偏好等记忆类型
 * @param content 规范化后的记忆正文
 * @param contentHash 正文 SHA-256，用于去重和完整性判断
 * @param embedding 向量表示
 * @param embeddingModel 生成当前向量的模型标识
 * @param reinforcement 相同记忆被强化的次数，至少为 1
 * @param emotionalWeight 0 到 10 的情绪权重
 * @param sourceKind 记忆来源类型
 * @param happenedAt 被记录事件的可选发生时间
 * @param revision 乐观并发控制使用的修订号
 * @param createdAt 首次创建时间
 * @param updatedAt 最近更新时间，不得早于创建时间
 */
public record MemoryItem(
    String id,
    MemoryScope scope,
    MemoryType type,
    String content,
    String contentHash,
    EmbeddingVector embedding,
    String embeddingModel,
    int reinforcement,
    int emotionalWeight,
    MemorySourceKind sourceKind,
    Instant happenedAt,
    long revision,
    Instant createdAt,
    Instant updatedAt,
    MemoryLifecycleState lifecycleState) {
  public MemoryItem {
    id = MemoryValueRules.itemId(id);
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(type, "type");
    content = MemoryValueRules.content(content);
    contentHash = MemoryValueRules.sha256(contentHash, "Content Hash");
    Objects.requireNonNull(embedding, "embedding");
    embeddingModel = MemoryValueRules.required(embeddingModel, "Embedding Model", 256);
    if (reinforcement < 1) {
      throw new IllegalArgumentException("Reinforcement 必须大于零");
    }
    if (emotionalWeight < 0 || emotionalWeight > 10) {
      throw new IllegalArgumentException("Emotional Weight 必须在 0..10");
    }
    Objects.requireNonNull(sourceKind, "sourceKind");
    if (revision < 1) {
      throw new IllegalArgumentException("Revision 必须大于零");
    }
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    Objects.requireNonNull(lifecycleState, "lifecycleState");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("Updated At 不能早于 Created At");
    }
  }

  public MemoryItem(
      String id,
      MemoryScope scope,
      MemoryType type,
      String content,
      String contentHash,
      EmbeddingVector embedding,
      String embeddingModel,
      int reinforcement,
      int emotionalWeight,
      MemorySourceKind sourceKind,
      Instant happenedAt,
      long revision,
      Instant createdAt,
      Instant updatedAt) {
    this(
        id,
        scope,
        type,
        content,
        contentHash,
        embedding,
        embeddingModel,
        reinforcement,
        emotionalWeight,
        sourceKind,
        happenedAt,
        revision,
        createdAt,
        updatedAt,
        MemoryLifecycleState.ACTIVE);
  }

  /** 返回向量维度，便于写入和查询前执行模型兼容校验。 */
  public int embeddingDimensions() {
    return embedding.dimensions();
  }

  @Override
  public String toString() {
    return "MemoryItem[type="
        + type
        + ", reinforcement="
        + reinforcement
        + ", revision="
        + revision
        + ", lifecycleState="
        + lifecycleState
        + "]";
  }
}
