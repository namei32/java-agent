package io.namei.agent.bootstrap.http;

import io.namei.agent.adapter.sqlite.JavaMemoryRepositoryException;
import io.namei.agent.application.MemoryDeleteOutcome;
import io.namei.agent.application.MemoryDeleteService;
import io.namei.agent.application.MemoryQueryService;
import io.namei.agent.application.MemoryView;
import io.namei.agent.application.MemoryWriteOutcome;
import io.namei.agent.application.MemoryWriteRequest;
import io.namei.agent.application.MemoryWriteService;
import java.util.List;
import java.util.Objects;

public interface MemoryManagementApi {
  MemoryWriteOutcome write(String sessionId, MemoryWriteRequest request);

  List<MemoryView> list(String sessionId);

  MemoryDeleteOutcome delete(String sessionId, String requestId, String memoryId);

  static MemoryManagementApi unavailable() {
    return new MemoryManagementApi() {
      @Override
      public MemoryWriteOutcome write(String sessionId, MemoryWriteRequest request) {
        throw new MemoryApiUnavailableException();
      }

      @Override
      public List<MemoryView> list(String sessionId) {
        throw new MemoryApiUnavailableException();
      }

      @Override
      public MemoryDeleteOutcome delete(String sessionId, String requestId, String memoryId) {
        throw new MemoryApiUnavailableException();
      }
    };
  }

  static MemoryManagementApi enabled(
      MemoryWriteService writes, MemoryQueryService queries, MemoryDeleteService deletes) {
    Objects.requireNonNull(writes, "writes");
    Objects.requireNonNull(queries, "queries");
    Objects.requireNonNull(deletes, "deletes");
    return new MemoryManagementApi() {
      @Override
      public MemoryWriteOutcome write(String sessionId, MemoryWriteRequest request) {
        try {
          return writes.write(sessionId, request);
        } catch (JavaMemoryRepositoryException exception) {
          throw new MemoryApiPersistenceException(exception);
        }
      }

      @Override
      public List<MemoryView> list(String sessionId) {
        try {
          return queries.list(sessionId);
        } catch (JavaMemoryRepositoryException exception) {
          throw new MemoryApiPersistenceException(exception);
        }
      }

      @Override
      public MemoryDeleteOutcome delete(String sessionId, String requestId, String memoryId) {
        try {
          return deletes.delete(sessionId, requestId, memoryId);
        } catch (JavaMemoryRepositoryException exception) {
          throw new MemoryApiPersistenceException(exception);
        }
      }
    };
  }
}
