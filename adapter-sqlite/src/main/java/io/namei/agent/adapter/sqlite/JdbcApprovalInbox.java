package io.namei.agent.adapter.sqlite;

import io.namei.agent.application.ApprovalInbox;
import io.namei.agent.application.ApprovalInboxDecision;
import io.namei.agent.application.ApprovalInboxEntry;
import io.namei.agent.application.ApprovalInboxReference;
import io.namei.agent.application.ApprovalInboxResolution;
import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.approval.ApprovalState;
import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** SQLite implementation with database-authoritative expiry and compare-and-set resolution. */
public final class JdbcApprovalInbox implements ApprovalInbox {
  static final int MAX_ENTRIES = 64;
  private static final Pattern ACTOR_REFERENCE = Pattern.compile("[A-Za-z0-9_-]{1,128}");
  private static final String COLUMNS =
      "approval_id, approval_ref, session_binding, turn_id, call_id, tool_name, tool_version, "
          + "risk, arguments_hash, idempotency_key, summary, issued_at, issued_epoch_second, "
          + "issued_nano, expires_at, expires_epoch_second, expires_nano, fingerprint_version, "
          + "fingerprint, state, decided_at, actor_reference";

  private final ApprovalInboxSchemaInitializer schema;

  public JdbcApprovalInbox(ApprovalInboxSchemaInitializer schema) {
    this.schema = Objects.requireNonNull(schema, "schema");
  }

  @Override
  public ApprovalInboxEntry create(ApprovalInboxEntry pending) {
    Objects.requireNonNull(pending, "pending");
    if (pending.state() != ApprovalState.PENDING) {
      throw new IllegalArgumentException("只能创建待审批记录");
    }
    try (var connection = schema.openConnection()) {
      return ApprovalInboxSchemaInitializer.transaction(
          connection,
          () -> {
            if (entryCount(connection) >= MAX_ENTRIES) {
              throw ApprovalInboxRepositoryException.capacityExceeded();
            }
            insert(connection, pending);
            return pending;
          });
    } catch (ApprovalInboxRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw ApprovalInboxRepositoryException.unavailable(exception);
    }
  }

  @Override
  public List<ApprovalInboxEntry> list(Instant observedAt, int limit) {
    Objects.requireNonNull(observedAt, "observedAt");
    if (limit < 1 || limit > 64) {
      throw new IllegalArgumentException("审批收件箱列表上限必须在 1..64");
    }
    try (var connection = schema.openConnection()) {
      return ApprovalInboxSchemaInitializer.transaction(
          connection,
          () -> {
            expireDue(connection, observedAt);
            return selectAll(connection, limit);
          });
    } catch (ApprovalInboxRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw ApprovalInboxRepositoryException.unavailable(exception);
    }
  }

  @Override
  public ApprovalInboxResolution resolve(
      ApprovalInboxReference reference,
      ApprovalInboxDecision decision,
      String actorReference,
      Instant decidedAt) {
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(decidedAt, "decidedAt");
    String actor = requireActorReference(actorReference);
    try (var connection = schema.openConnection()) {
      return ApprovalInboxSchemaInitializer.transaction(
          connection,
          () -> resolveInTransaction(connection, reference, decision, actor, decidedAt));
    } catch (ApprovalInboxRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw ApprovalInboxRepositoryException.unavailable(exception);
    }
  }

  private static ApprovalInboxResolution resolveInTransaction(
      Connection connection,
      ApprovalInboxReference reference,
      ApprovalInboxDecision decision,
      String actorReference,
      Instant decidedAt)
      throws SQLException {
    expireDue(connection, decidedAt);
    Optional<ApprovalInboxEntry> entry = find(connection, reference);
    if (entry.isEmpty()) {
      return ApprovalInboxResolution.notFound();
    }
    ApprovalInboxEntry current = entry.orElseThrow();
    if (current.state() == ApprovalState.EXPIRED) {
      return ApprovalInboxResolution.expired(current);
    }
    if (current.state() != ApprovalState.PENDING) {
      return ApprovalInboxResolution.alreadyResolved(current);
    }
    if (decidedAt.isBefore(current.request().issuedAt())) {
      throw new IllegalArgumentException("审批决定时间不能早于签发时间");
    }
    ApprovalState state =
        decision == ApprovalInboxDecision.APPROVED ? ApprovalState.APPROVED : ApprovalState.DENIED;
    int updated;
    try (var statement =
        connection.prepareStatement(
            "UPDATE approval_inbox_entries SET state = ?, decided_at = ?, actor_reference = ? "
                + "WHERE approval_ref = ? AND state = 'PENDING' AND "
                + "(expires_epoch_second > ? OR (expires_epoch_second = ? AND expires_nano > ?))")) {
      statement.setString(1, state.name());
      statement.setString(2, decidedAt.toString());
      statement.setString(3, actorReference);
      statement.setString(4, reference.value());
      statement.setLong(5, decidedAt.getEpochSecond());
      statement.setLong(6, decidedAt.getEpochSecond());
      statement.setInt(7, decidedAt.getNano());
      updated = statement.executeUpdate();
    }
    if (updated == 1) {
      return ApprovalInboxResolution.resolved(
          find(connection, reference)
              .orElseThrow(() -> ApprovalInboxRepositoryException.unavailable(null)));
    }
    if (updated != 0) {
      throw ApprovalInboxRepositoryException.unavailable(null);
    }
    expireDue(connection, decidedAt);
    ApprovalInboxEntry refreshed =
        find(connection, reference)
            .orElseThrow(() -> ApprovalInboxRepositoryException.unavailable(null));
    return refreshed.state() == ApprovalState.EXPIRED
        ? ApprovalInboxResolution.expired(refreshed)
        : ApprovalInboxResolution.alreadyResolved(refreshed);
  }

  static void insert(Connection connection, ApprovalInboxEntry entry) throws SQLException {
    ApprovalRequest request = entry.request();
    try (var statement =
        connection.prepareStatement(
            "INSERT INTO approval_inbox_entries ("
                + COLUMNS
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      statement.setString(1, request.approvalId());
      statement.setString(2, entry.reference().value());
      statement.setString(3, storedBinding("session", request.sessionBinding()));
      statement.setString(4, storedBinding("turn", request.turnId()));
      statement.setString(5, storedBinding("call", request.callId()));
      statement.setString(6, request.toolName());
      statement.setString(7, request.toolVersion());
      statement.setString(8, request.risk().name());
      statement.setString(9, request.argumentsHash());
      statement.setString(10, request.idempotencyKey());
      statement.setString(11, request.summary());
      statement.setString(12, request.issuedAt().toString());
      statement.setLong(13, request.issuedAt().getEpochSecond());
      statement.setInt(14, request.issuedAt().getNano());
      statement.setString(15, request.expiresAt().toString());
      statement.setLong(16, request.expiresAt().getEpochSecond());
      statement.setInt(17, request.expiresAt().getNano());
      statement.setString(18, request.fingerprintVersion());
      statement.setString(19, request.fingerprint());
      statement.setString(20, entry.state().name());
      if (entry.decidedAt() == null) {
        statement.setNull(21, java.sql.Types.VARCHAR);
      } else {
        statement.setString(21, entry.decidedAt().toString());
      }
      statement.setString(22, entry.actorReference());
      if (statement.executeUpdate() != 1) {
        throw ApprovalInboxRepositoryException.unavailable(null);
      }
    }
  }

  static int entryCount(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT COUNT(*) FROM approval_inbox_entries")) {
      if (!rows.next()) {
        throw ApprovalInboxRepositoryException.unavailable(null);
      }
      long count = rows.getLong(1);
      if (rows.wasNull() || count < 0 || count > Integer.MAX_VALUE || rows.next()) {
        throw ApprovalInboxRepositoryException.unavailable(null);
      }
      return (int) count;
    }
  }

  private static void expireDue(Connection connection, Instant observedAt) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "UPDATE approval_inbox_entries SET state = 'EXPIRED', decided_at = ?, actor_reference = '' "
                + "WHERE state = 'PENDING' AND (expires_epoch_second < ? "
                + "OR (expires_epoch_second = ? AND expires_nano <= ?))")) {
      statement.setString(1, observedAt.toString());
      statement.setLong(2, observedAt.getEpochSecond());
      statement.setLong(3, observedAt.getEpochSecond());
      statement.setInt(4, observedAt.getNano());
      int updated = statement.executeUpdate();
      if (updated < 0) {
        throw ApprovalInboxRepositoryException.unavailable(null);
      }
    }
  }

  private static List<ApprovalInboxEntry> selectAll(Connection connection, int limit)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT "
                + COLUMNS
                + " FROM approval_inbox_entries ORDER BY issued_epoch_second ASC, issued_nano ASC, "
                + "approval_ref ASC LIMIT ?")) {
      statement.setInt(1, limit);
      try (var rows = statement.executeQuery()) {
        List<ApprovalInboxEntry> entries = new ArrayList<>();
        while (rows.next()) {
          entries.add(read(rows));
        }
        return List.copyOf(entries);
      }
    }
  }

  private static Optional<ApprovalInboxEntry> find(
      Connection connection, ApprovalInboxReference reference) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT " + COLUMNS + " FROM approval_inbox_entries WHERE approval_ref = ?")) {
      statement.setString(1, reference.value());
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        ApprovalInboxEntry entry = read(rows);
        if (rows.next()) {
          throw ApprovalInboxRepositoryException.unavailable(null);
        }
        return Optional.of(entry);
      }
    }
  }

  private static ApprovalInboxEntry read(ResultSet rows) throws SQLException {
    Instant issuedAt = Instant.parse(rows.getString("issued_at"));
    Instant expiresAt = Instant.parse(rows.getString("expires_at"));
    String decided = rows.getString("decided_at");
    Instant decidedAt = decided == null ? null : Instant.parse(decided);
    ApprovalRequest request =
        new ApprovalRequest(
            rows.getString("approval_id"),
            rows.getString("session_binding"),
            rows.getString("turn_id"),
            rows.getString("call_id"),
            rows.getString("tool_name"),
            rows.getString("tool_version"),
            ToolRisk.valueOf(rows.getString("risk")),
            rows.getString("arguments_hash"),
            rows.getString("idempotency_key"),
            rows.getString("summary"),
            issuedAt,
            expiresAt,
            rows.getString("fingerprint_version"),
            rows.getString("fingerprint"));
    return new ApprovalInboxEntry(
        ApprovalInboxReference.of(rows.getString("approval_ref")),
        request,
        ApprovalState.valueOf(rows.getString("state")),
        decidedAt,
        rows.getString("actor_reference"));
  }

  private static String requireActorReference(String value) {
    String normalized = Objects.requireNonNull(value, "actorReference").strip();
    if (!ACTOR_REFERENCE.matcher(normalized).matches()) {
      throw new IllegalArgumentException("审批主体引用无效");
    }
    return normalized;
  }

  private static String storedBinding(String purpose, String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update("approval-inbox-binding-v1".getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(purpose.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(value.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JDK 缺少 SHA-256", exception);
    }
  }
}
