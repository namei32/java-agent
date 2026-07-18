package io.namei.agent.application;

import java.util.Optional;

public interface PendingOperationKeyProvider {
  PendingOperationKey current();

  Optional<PendingOperationKey> findByKeyId(String keyId);
}
