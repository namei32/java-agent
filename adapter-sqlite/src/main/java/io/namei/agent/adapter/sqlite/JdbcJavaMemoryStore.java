package io.namei.agent.adapter.sqlite;

import io.namei.agent.kernel.memory.MemoryCandidateLimitExceededException;
import io.namei.agent.kernel.memory.MemoryDeleteCommand;
import io.namei.agent.kernel.memory.MemoryDeleteResult;
import io.namei.agent.kernel.memory.MemoryDeleteStatus;
import io.namei.agent.kernel.memory.MemoryForgetCommand;
import io.namei.agent.kernel.memory.MemoryForgetResult;
import io.namei.agent.kernel.memory.MemoryIdempotencyConflictException;
import io.namei.agent.kernel.memory.MemoryItem;
import io.namei.agent.kernel.memory.MemoryLifecycleState;
import io.namei.agent.kernel.memory.MemoryMutation;
import io.namei.agent.kernel.memory.MemoryMutationKey;
import io.namei.agent.kernel.memory.MemoryMutationOperation;
import io.namei.agent.kernel.memory.MemoryMutationStatus;
import io.namei.agent.kernel.memory.MemoryScope;
import io.namei.agent.kernel.memory.MemorySearchRequest;
import io.namei.agent.kernel.memory.MemorySourceKind;
import io.namei.agent.kernel.memory.MemoryType;
import io.namei.agent.kernel.memory.MemoryWriteCommand;
import io.namei.agent.kernel.memory.MemoryWriteReplayQuery;
import io.namei.agent.kernel.memory.MemoryWriteResult;
import io.namei.agent.kernel.memory.MemoryWriteStatus;
import io.namei.agent.kernel.port.MemorySoftForgetPort;
import io.namei.agent.kernel.port.MemoryStorePort;
import io.namei.agent.kernel.port.MemoryWritePort;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class JdbcJavaMemoryStore
    implements MemoryStorePort, MemoryWritePort, MemorySoftForgetPort {
  private static final String ITEM_COLUMNS =
      "id, scope_binding, memory_type, content, content_hash, embedding, embedding_model, "
          + "embedding_dimensions, reinforcement, emotional_weight, source_kind, happened_at, "
          + "revision, created_at, updated_at, status";

  private final JavaMemorySchemaInitializer schema;
  private final Float32VectorCodec vectorCodec;

  public JdbcJavaMemoryStore(JavaMemorySchemaInitializer schema, Float32VectorCodec vectorCodec) {
    this.schema = Objects.requireNonNull(schema, "schema");
    this.vectorCodec = Objects.requireNonNull(vectorCodec, "vectorCodec");
  }

  @Override
  public long candidateCount(MemoryScope scope) {
    Objects.requireNonNull(scope, "scope");
    try (var connection = schema.openConnection()) {
      return countCandidates(connection, scope);
    } catch (JavaMemoryRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw JavaMemoryRepositoryException.operationFailed(exception);
    }
  }

  @Override
  public List<MemoryItem> loadCandidates(MemorySearchRequest request) {
    Objects.requireNonNull(request, "request");
    try (var connection = schema.openConnection()) {
      return transaction(
          connection,
          false,
          () -> {
            if (countCandidates(connection, request.scope()) > request.maxCandidates()) {
              throw new MemoryCandidateLimitExceededException();
            }
            return selectCandidates(connection, request);
          });
    } catch (MemoryCandidateLimitExceededException | JavaMemoryRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw JavaMemoryRepositoryException.operationFailed(exception);
    }
  }

  @Override
  public List<MemoryItem> list(MemoryScope scope, int limit) {
    Objects.requireNonNull(scope, "scope");
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Memory 列表上限必须在 1..100");
    }
    try (var connection = schema.openConnection();
        var statement =
            connection.prepareStatement(
                "SELECT "
                    + ITEM_COLUMNS
                    + " FROM memory_items WHERE scope_binding = ? AND status = 'ACTIVE' "
                    + "ORDER BY updated_at DESC, id ASC LIMIT ?")) {
      statement.setString(1, scope.binding());
      statement.setInt(2, limit);
      try (var rows = statement.executeQuery()) {
        return readItems(rows);
      }
    } catch (JavaMemoryRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw JavaMemoryRepositoryException.operationFailed(exception);
    }
  }

  @Override
  public Optional<MemoryMutation> findMutation(MemoryMutationKey key) {
    Objects.requireNonNull(key, "key");
    try (var connection = schema.openConnection()) {
      return findMutation(connection, key);
    } catch (JavaMemoryRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw JavaMemoryRepositoryException.operationFailed(exception);
    }
  }

  @Override
  public Optional<MemoryWriteResult> replayUpsert(MemoryWriteReplayQuery query) {
    Objects.requireNonNull(query, "query");
    try (var connection = schema.openConnection()) {
      return transaction(
          connection,
          false,
          () -> {
            Optional<MemoryMutation> recorded = findMutation(connection, query.key());
            if (recorded.isEmpty()) {
              return Optional.empty();
            }
            return Optional.of(
                replayUpsert(
                    connection, query.key().scope(), query.argumentHash(), recorded.orElseThrow()));
          });
    } catch (MemoryIdempotencyConflictException | JavaMemoryRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw JavaMemoryRepositoryException.operationFailed(exception);
    }
  }

  @Override
  public MemoryWriteResult upsert(MemoryWriteCommand command) {
    Objects.requireNonNull(command, "command");
    MemoryMutationKey key = new MemoryMutationKey(command.scope(), command.requestId());
    Optional<MemoryMutation> recorded = findMutation(key);
    if (recorded.isPresent()) {
      return replayUpsert(command, recorded.orElseThrow());
    }

    try (var connection = schema.openConnection()) {
      return transaction(connection, true, () -> upsertInTransaction(connection, command, key));
    } catch (MemoryIdempotencyConflictException | JavaMemoryRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw JavaMemoryRepositoryException.operationFailed(exception);
    }
  }

  @Override
  public MemoryDeleteResult delete(MemoryDeleteCommand command) {
    Objects.requireNonNull(command, "command");
    MemoryMutationKey key = new MemoryMutationKey(command.scope(), command.requestId());
    Optional<MemoryMutation> recorded = findMutation(key);
    if (recorded.isPresent()) {
      return replayDelete(command, recorded.orElseThrow());
    }

    try (var connection = schema.openConnection()) {
      return transaction(connection, true, () -> deleteInTransaction(connection, command, key));
    } catch (MemoryIdempotencyConflictException | JavaMemoryRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw JavaMemoryRepositoryException.operationFailed(exception);
    }
  }

  @Override
  public MemoryForgetResult softForget(MemoryForgetCommand command) {
    Objects.requireNonNull(command, "command");
    try (var connection = schema.openConnection()) {
      return transaction(connection, true, () -> softForgetInTransaction(connection, command));
    } catch (MemoryIdempotencyConflictException | JavaMemoryRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw JavaMemoryRepositoryException.operationFailed(exception);
    }
  }

  private MemoryWriteResult replayUpsert(MemoryWriteCommand command, MemoryMutation mutation) {
    try (var connection = schema.openConnection()) {
      return replayUpsert(connection, command.scope(), command.argumentHash(), mutation);
    } catch (MemoryIdempotencyConflictException | JavaMemoryRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw JavaMemoryRepositoryException.operationFailed(exception);
    }
  }

  private MemoryWriteResult upsertInTransaction(
      Connection connection, MemoryWriteCommand command, MemoryMutationKey key)
      throws SQLException {
    Optional<MemoryMutation> recorded = findMutation(connection, key);
    if (recorded.isPresent()) {
      return replayUpsert(
          connection, command.scope(), command.argumentHash(), recorded.orElseThrow());
    }

    Optional<MemoryItem> existing = findExactItem(connection, command);
    MemoryItem item;
    MemoryWriteStatus status;
    if (existing.isPresent()) {
      item = reinforcedItem(existing.orElseThrow(), command);
      updateReinforcedItem(connection, item);
      status = MemoryWriteStatus.REINFORCED;
    } else {
      item = newItem(command);
      insertItem(connection, item);
      status = MemoryWriteStatus.CREATED;
    }
    insertMutation(
        connection,
        key,
        MemoryMutationOperation.UPSERT,
        command.argumentHash(),
        item.id(),
        mutationStatus(status),
        command.requestedAt());
    return new MemoryWriteResult(status, item);
  }

  private MemoryDeleteResult deleteInTransaction(
      Connection connection, MemoryDeleteCommand command, MemoryMutationKey key)
      throws SQLException {
    Optional<MemoryMutation> recorded = findMutation(connection, key);
    if (recorded.isPresent()) {
      return replayDelete(command, recorded.orElseThrow());
    }

    int deleted;
    try (var statement =
        connection.prepareStatement(
            "DELETE FROM memory_items WHERE scope_binding = ? AND id = ?")) {
      statement.setString(1, command.scope().binding());
      statement.setString(2, command.itemId());
      deleted = statement.executeUpdate();
    }
    if (deleted < 0 || deleted > 1) {
      throw new SQLException("unexpected memory delete count");
    }
    MemoryDeleteStatus status =
        deleted == 1 ? MemoryDeleteStatus.DELETED : MemoryDeleteStatus.NOT_FOUND;
    insertMutation(
        connection,
        key,
        MemoryMutationOperation.DELETE,
        command.argumentHash(),
        command.itemId(),
        mutationStatus(status),
        command.requestedAt());
    return new MemoryDeleteResult(status, command.itemId());
  }

  private MemoryForgetResult softForgetInTransaction(
      Connection connection, MemoryForgetCommand command) throws SQLException {
    Optional<Long> recorded = findForgetMutation(connection, command);
    if (recorded.isPresent()) {
      return replayForget(connection, command, recorded.orElseThrow());
    }

    long mutationId = insertForgetMutation(connection, command);
    var supersededIds = new ArrayList<String>();
    var missingIds = new ArrayList<String>();
    int ordinal = 0;
    for (String itemId : command.requestedIds()) {
      int affected;
      try (var update =
          connection.prepareStatement(
              """
              UPDATE memory_items
              SET status = 'SUPERSEDED', revision = revision + 1, updated_at = ?
              WHERE scope_binding = ? AND id = ?
              """)) {
        update.setString(1, command.requestedAt().toString());
        update.setString(2, command.scope().binding());
        update.setString(3, itemId);
        affected = update.executeUpdate();
      }
      if (affected < 0 || affected > 1) {
        throw new SQLException("unexpected memory soft forget count");
      }
      String resultStatus;
      if (affected == 1) {
        supersededIds.add(itemId);
        resultStatus = "SUPERSEDED";
      } else {
        missingIds.add(itemId);
        resultStatus = "MISSING";
      }
      insertForgetMutationItem(connection, mutationId, ordinal++, itemId, resultStatus);
    }
    return new MemoryForgetResult(command.requestedIds(), supersededIds, missingIds);
  }

  private static MemoryDeleteResult replayDelete(
      MemoryDeleteCommand command, MemoryMutation mutation) {
    requireMatchingMutation(mutation, MemoryMutationOperation.DELETE, command.argumentHash());
    MemoryDeleteStatus status =
        switch (mutation.status()) {
          case DELETED -> MemoryDeleteStatus.DELETED;
          case NOT_FOUND -> MemoryDeleteStatus.NOT_FOUND;
          case CREATED, REINFORCED, FORGOTTEN ->
              throw JavaMemoryRepositoryException.operationFailed(null);
        };
    return new MemoryDeleteResult(status, mutation.itemId());
  }

  private MemoryWriteResult replayUpsert(
      Connection connection, MemoryScope scope, String argumentHash, MemoryMutation mutation)
      throws SQLException {
    requireMatchingMutation(mutation, MemoryMutationOperation.UPSERT, argumentHash);
    MemoryWriteStatus status =
        switch (mutation.status()) {
          case CREATED -> MemoryWriteStatus.CREATED;
          case REINFORCED -> MemoryWriteStatus.REINFORCED;
          case DELETED, NOT_FOUND, FORGOTTEN ->
              throw JavaMemoryRepositoryException.operationFailed(null);
        };
    MemoryItem item =
        findItem(connection, scope, mutation.itemId())
            .orElseThrow(() -> JavaMemoryRepositoryException.operationFailed(null));
    return new MemoryWriteResult(status, item);
  }

  private static void requireMatchingMutation(
      MemoryMutation mutation, MemoryMutationOperation operation, String argumentHash) {
    if (mutation.operation() != operation || !mutation.argumentHash().equals(argumentHash)) {
      throw new MemoryIdempotencyConflictException();
    }
  }

  private long countCandidates(Connection connection, MemoryScope scope) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT COUNT(*) FROM memory_items WHERE scope_binding = ? AND status = 'ACTIVE'")) {
      statement.setString(1, scope.binding());
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new SQLException("memory count missing");
        }
        long count = rows.getLong(1);
        if (rows.wasNull() || count < 0 || rows.next()) {
          throw new SQLException("invalid memory count");
        }
        return count;
      }
    }
  }

  private List<MemoryItem> selectCandidates(Connection connection, MemorySearchRequest request)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT "
                + ITEM_COLUMNS
                + " FROM memory_items WHERE scope_binding = ? AND status = 'ACTIVE' "
                + "AND embedding_model = ? "
                + "AND embedding_dimensions = ? ORDER BY updated_at DESC, id ASC")) {
      statement.setString(1, request.scope().binding());
      statement.setString(2, request.embeddingModel());
      statement.setInt(3, request.queryEmbedding().dimensions());
      try (var rows = statement.executeQuery()) {
        return readItems(rows);
      }
    }
  }

  private Optional<MemoryMutation> findMutation(Connection connection, MemoryMutationKey key)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT operation, argument_hash, item_id, result_status, created_at "
                + "FROM memory_mutations WHERE scope_binding = ? AND request_id = ?")) {
      statement.setString(1, key.scope().binding());
      statement.setString(2, key.requestId());
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        MemoryMutation mutation = readMutation(rows);
        if (rows.next()) {
          throw new SQLException("duplicate memory mutation");
        }
        return Optional.of(mutation);
      }
    }
  }

  private Optional<Long> findForgetMutation(Connection connection, MemoryForgetCommand command)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT id, operation, argument_hash FROM memory_mutations "
                + "WHERE scope_binding = ? AND request_id = ?")) {
      statement.setString(1, command.scope().binding());
      statement.setString(2, command.operationKey());
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        long mutationId = exactLong(rows, "id");
        if (!MemoryMutationOperation.FORGET.name().equals(rows.getString("operation"))
            || !command.argumentHash().equals(rows.getString("argument_hash"))) {
          throw new MemoryIdempotencyConflictException();
        }
        if (rows.next()) {
          throw new SQLException("duplicate memory forget mutation");
        }
        return Optional.of(mutationId);
      }
    }
  }

  private static long insertForgetMutation(Connection connection, MemoryForgetCommand command)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            INSERT INTO memory_mutations (
              scope_binding, request_id, operation, argument_hash,
              item_id, result_status, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
      statement.setString(1, command.scope().binding());
      statement.setString(2, command.operationKey());
      statement.setString(3, MemoryMutationOperation.FORGET.name());
      statement.setString(4, command.argumentHash());
      statement.setString(5, "batch");
      statement.setString(6, MemoryMutationStatus.FORGOTTEN.name());
      statement.setString(7, command.requestedAt().toString());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("memory forget mutation insert count is not one");
      }
    }
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT last_insert_rowid()")) {
      if (!rows.next()) {
        throw new SQLException("memory forget mutation id missing");
      }
      long mutationId = rows.getLong(1);
      if (rows.wasNull() || mutationId < 1 || rows.next()) {
        throw new SQLException("invalid memory forget mutation id");
      }
      return mutationId;
    }
  }

  private static void insertForgetMutationItem(
      Connection connection, long mutationId, int ordinal, String itemId, String resultStatus)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            INSERT INTO memory_mutation_items (mutation_id, ordinal, item_id, result_status)
            VALUES (?, ?, ?, ?)
            """)) {
      statement.setLong(1, mutationId);
      statement.setInt(2, ordinal);
      statement.setString(3, itemId);
      statement.setString(4, resultStatus);
      if (statement.executeUpdate() != 1) {
        throw new SQLException("memory forget mutation item insert count is not one");
      }
    }
  }

  private static MemoryForgetResult replayForget(
      Connection connection, MemoryForgetCommand command, long mutationId) throws SQLException {
    var supersededIds = new ArrayList<String>();
    var missingIds = new ArrayList<String>();
    int ordinal = 0;
    try (var statement =
        connection.prepareStatement(
            "SELECT ordinal, item_id, result_status FROM memory_mutation_items "
                + "WHERE mutation_id = ? ORDER BY ordinal ASC")) {
      statement.setLong(1, mutationId);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          if (exactInt(rows, "ordinal") != ordinal
              || ordinal >= command.requestedIds().size()
              || !command.requestedIds().get(ordinal).equals(rows.getString("item_id"))) {
            throw JavaMemoryRepositoryException.operationFailed(null);
          }
          switch (rows.getString("result_status")) {
            case "SUPERSEDED" -> supersededIds.add(command.requestedIds().get(ordinal));
            case "MISSING" -> missingIds.add(command.requestedIds().get(ordinal));
            default -> throw JavaMemoryRepositoryException.operationFailed(null);
          }
          ordinal++;
        }
      }
    }
    if (ordinal != command.requestedIds().size()) {
      throw JavaMemoryRepositoryException.operationFailed(null);
    }
    return new MemoryForgetResult(command.requestedIds(), supersededIds, missingIds);
  }

  private Optional<MemoryItem> findExactItem(Connection connection, MemoryWriteCommand command)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT "
                + ITEM_COLUMNS
                + " FROM memory_items WHERE scope_binding = ? AND memory_type = ? "
                + "AND content_hash = ?")) {
      statement.setString(1, command.scope().binding());
      statement.setString(2, command.type().name());
      statement.setString(3, command.contentHash());
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        MemoryItem item = readItem(rows);
        if (rows.next()) {
          throw new SQLException("duplicate exact memory item");
        }
        return Optional.of(item);
      }
    }
  }

  private Optional<MemoryItem> findItem(Connection connection, MemoryScope scope, String itemId)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT " + ITEM_COLUMNS + " FROM memory_items WHERE scope_binding = ? AND id = ?")) {
      statement.setString(1, scope.binding());
      statement.setString(2, itemId);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        MemoryItem item = readItem(rows);
        if (rows.next()) {
          throw new SQLException("duplicate memory item id");
        }
        return Optional.of(item);
      }
    }
  }

  private static MemoryItem newItem(MemoryWriteCommand command) {
    return new MemoryItem(
        command.itemId(),
        command.scope(),
        command.type(),
        command.content(),
        command.contentHash(),
        command.embedding(),
        command.embeddingModel(),
        1,
        command.emotionalWeight(),
        command.sourceKind(),
        command.happenedAt(),
        1,
        command.requestedAt(),
        command.requestedAt());
  }

  private static MemoryItem reinforcedItem(MemoryItem existing, MemoryWriteCommand command) {
    return new MemoryItem(
        existing.id(),
        existing.scope(),
        existing.type(),
        existing.content(),
        existing.contentHash(),
        existing.embedding(),
        existing.embeddingModel(),
        Math.addExact(existing.reinforcement(), 1),
        Math.max(existing.emotionalWeight(), command.emotionalWeight()),
        existing.sourceKind(),
        existing.happenedAt(),
        Math.addExact(existing.revision(), 1),
        existing.createdAt(),
        latest(existing.updatedAt(), command.requestedAt()),
        MemoryLifecycleState.ACTIVE);
  }

  private static Instant latest(Instant first, Instant second) {
    return first.isAfter(second) ? first : second;
  }

  private void insertItem(Connection connection, MemoryItem item) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            INSERT INTO memory_items (
              id, scope_binding, memory_type, content, content_hash, embedding,
              embedding_model, embedding_dimensions, reinforcement, emotional_weight,
              source_kind, happened_at, revision, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
      statement.setString(1, item.id());
      statement.setString(2, item.scope().binding());
      statement.setString(3, item.type().name());
      statement.setString(4, item.content());
      statement.setString(5, item.contentHash());
      statement.setBytes(6, vectorCodec.encode(item.embedding()));
      statement.setString(7, item.embeddingModel());
      statement.setInt(8, item.embeddingDimensions());
      statement.setInt(9, item.reinforcement());
      statement.setInt(10, item.emotionalWeight());
      statement.setString(11, item.sourceKind().name());
      setInstant(statement, 12, item.happenedAt());
      statement.setLong(13, item.revision());
      statement.setString(14, item.createdAt().toString());
      statement.setString(15, item.updatedAt().toString());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("memory insert count is not one");
      }
    }
  }

  private static void updateReinforcedItem(Connection connection, MemoryItem item)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            UPDATE memory_items
            SET reinforcement = ?, emotional_weight = ?, revision = ?, updated_at = ?, status = ?
            WHERE scope_binding = ? AND id = ? AND revision = ?
            """)) {
      statement.setInt(1, item.reinforcement());
      statement.setInt(2, item.emotionalWeight());
      statement.setLong(3, item.revision());
      statement.setString(4, item.updatedAt().toString());
      statement.setString(5, item.lifecycleState().name());
      statement.setString(6, item.scope().binding());
      statement.setString(7, item.id());
      statement.setLong(8, item.revision() - 1);
      if (statement.executeUpdate() != 1) {
        throw new SQLException("memory reinforce count is not one");
      }
    }
  }

  private static void insertMutation(
      Connection connection,
      MemoryMutationKey key,
      MemoryMutationOperation operation,
      String argumentHash,
      String itemId,
      MemoryMutationStatus status,
      Instant createdAt)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            INSERT INTO memory_mutations (
              scope_binding, request_id, operation, argument_hash,
              item_id, result_status, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
      statement.setString(1, key.scope().binding());
      statement.setString(2, key.requestId());
      statement.setString(3, operation.name());
      statement.setString(4, argumentHash);
      statement.setString(5, itemId);
      statement.setString(6, status.name());
      statement.setString(7, createdAt.toString());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("memory mutation insert count is not one");
      }
    }
  }

  private List<MemoryItem> readItems(ResultSet rows) throws SQLException {
    var items = new ArrayList<MemoryItem>();
    while (rows.next()) {
      items.add(readItem(rows));
    }
    return List.copyOf(items);
  }

  private MemoryItem readItem(ResultSet rows) throws SQLException {
    String happenedAt = rows.getString("happened_at");
    int dimensions = exactInt(rows, "embedding_dimensions");
    return new MemoryItem(
        rows.getString("id"),
        new MemoryScope(rows.getString("scope_binding")),
        MemoryType.valueOf(rows.getString("memory_type")),
        rows.getString("content"),
        rows.getString("content_hash"),
        vectorCodec.decode(rows.getBytes("embedding"), dimensions),
        rows.getString("embedding_model"),
        exactInt(rows, "reinforcement"),
        exactInt(rows, "emotional_weight"),
        MemorySourceKind.valueOf(rows.getString("source_kind")),
        happenedAt == null ? null : Instant.parse(happenedAt),
        exactLong(rows, "revision"),
        Instant.parse(rows.getString("created_at")),
        Instant.parse(rows.getString("updated_at")),
        MemoryLifecycleState.valueOf(rows.getString("status")));
  }

  private static MemoryMutation readMutation(ResultSet rows) throws SQLException {
    return new MemoryMutation(
        MemoryMutationOperation.valueOf(rows.getString("operation")),
        rows.getString("argument_hash"),
        rows.getString("item_id"),
        MemoryMutationStatus.valueOf(rows.getString("result_status")),
        Instant.parse(rows.getString("created_at")));
  }

  private static int exactInt(ResultSet rows, String column) throws SQLException {
    long value = exactLong(rows, column);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new SQLException("integer column is out of range");
    }
    return Math.toIntExact(value);
  }

  private static long exactLong(ResultSet rows, String column) throws SQLException {
    long value = rows.getLong(column);
    if (rows.wasNull()) {
      throw new SQLException("required integer column is null");
    }
    return value;
  }

  private static void setInstant(java.sql.PreparedStatement statement, int index, Instant value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.VARCHAR);
    } else {
      statement.setString(index, value.toString());
    }
  }

  private static MemoryMutationStatus mutationStatus(MemoryWriteStatus status) {
    return switch (status) {
      case CREATED -> MemoryMutationStatus.CREATED;
      case REINFORCED -> MemoryMutationStatus.REINFORCED;
    };
  }

  private static MemoryMutationStatus mutationStatus(MemoryDeleteStatus status) {
    return switch (status) {
      case DELETED -> MemoryMutationStatus.DELETED;
      case NOT_FOUND -> MemoryMutationStatus.NOT_FOUND;
    };
  }

  private static <T> T transaction(Connection connection, boolean immediate, SqlWork<T> work)
      throws SQLException {
    executeTransactionControl(connection, immediate ? "BEGIN IMMEDIATE" : "BEGIN");
    try {
      T result = work.run();
      executeTransactionControl(connection, "COMMIT");
      return result;
    } catch (SQLException | RuntimeException exception) {
      rollbackPreservingFailure(connection, exception);
      throw exception;
    }
  }

  private static void executeTransactionControl(Connection connection, String command)
      throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(command);
    }
  }

  private static void rollbackPreservingFailure(Connection connection, Exception failure) {
    try {
      executeTransactionControl(connection, "ROLLBACK");
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T run() throws SQLException;
  }
}
