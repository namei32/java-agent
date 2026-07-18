package io.namei.agent.application;

import java.util.Objects;
import java.util.Set;

@FunctionalInterface
public interface ProactiveDedupe {
  boolean isKnown(String idempotencyKey);

  static ProactiveDedupe none() {
    return ignored -> false;
  }

  static ProactiveDedupe known(String idempotencyKey) {
    return known(Set.of(Objects.requireNonNull(idempotencyKey, "idempotencyKey")));
  }

  static ProactiveDedupe known(Set<String> idempotencyKeys) {
    Set<String> copied = Set.copyOf(Objects.requireNonNull(idempotencyKeys, "idempotencyKeys"));
    return copied::contains;
  }
}
