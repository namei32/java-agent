package io.namei.agent.adapter.sqlite;

import io.namei.agent.kernel.port.ProactiveJobStore;
import io.namei.agent.kernel.proactive.ProactiveJobLease;
import io.namei.agent.kernel.proactive.ProactiveJobRef;
import io.namei.agent.kernel.proactive.ProactiveJobState;
import io.namei.agent.kernel.proactive.ProactiveSchedule;
import io.namei.agent.kernel.proactive.ProactiveScheduleKind;
import io.namei.agent.kernel.proactive.ScheduledJob;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

/** SQLite implementation whose short transactions never include planner or delivery execution. */
public final class JdbcProactiveJobStore implements ProactiveJobStore {
  private final ProactiveSchemaInitializer schema;
  private final Clock clock;

  public JdbcProactiveJobStore(ProactiveSchemaInitializer schema) {
    this(schema, Clock.systemUTC());
  }

  JdbcProactiveJobStore(ProactiveSchemaInitializer schema, Clock clock) {
    this.schema = Objects.requireNonNull(schema, "schema");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void schedule(ScheduledJob job) {
    Objects.requireNonNull(job, "job");
    if (job.state() != ProactiveJobState.SCHEDULED) {
      throw new IllegalArgumentException("新 Proactive Job 必须为 SCHEDULED");
    }
    try (var connection = schema.openConnection();
        var insert =
            connection.prepareStatement(
                """
                INSERT INTO proactive_jobs(
                  job_ref, schedule_kind, next_run_at, every_millis, target_hash, idempotency_key,
                  state, attempts, max_attempts, owner_id, lease_expires_at, revision, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 0, ?)
                """)) {
      bindJob(insert, job, clock.instant());
      insert.executeUpdate();
    } catch (SQLException exception) {
      if (exception.getMessage() != null && exception.getMessage().contains("UNIQUE")) {
        throw ProactiveRepositoryException.duplicate(exception);
      }
      throw ProactiveRepositoryException.operationFailed(exception);
    }
  }

  @Override
  public Optional<ScheduledJob> find(ProactiveJobRef jobRef) {
    Objects.requireNonNull(jobRef, "jobRef");
    try (var connection = schema.openConnection();
        var select =
            connection.prepareStatement(
                """
                SELECT job_ref, schedule_kind, next_run_at, every_millis, target_hash, idempotency_key,
                       state, attempts, max_attempts, revision
                  FROM proactive_jobs
                 WHERE job_ref = ?
                """)) {
      select.setString(1, jobRef.value());
      try (var rows = select.executeQuery()) {
        return rows.next() ? Optional.of(read(rows).job()) : Optional.empty();
      }
    } catch (SQLException exception) {
      throw ProactiveRepositoryException.operationFailed(exception);
    }
  }

  @Override
  public Optional<ProactiveJobLease> claimNext(
      Instant now, String ownerId, Duration leaseDuration) {
    Objects.requireNonNull(now, "now");
    validateLease(ownerId, leaseDuration);
    Instant expiresAt = now.plus(leaseDuration);
    try (var connection = schema.openConnection()) {
      return transaction(connection, () -> claim(connection, now, ownerId, expiresAt));
    } catch (SQLException exception) {
      throw ProactiveRepositoryException.operationFailed(exception);
    }
  }

  @Override
  public Optional<ProactiveJobLease> markRunning(ProactiveJobLease lease, Instant now) {
    Objects.requireNonNull(lease, "lease");
    Objects.requireNonNull(now, "now");
    try (var connection = schema.openConnection()) {
      return transaction(
          connection,
          () -> {
            try (var update =
                connection.prepareStatement(
                    """
                    UPDATE proactive_jobs
                       SET state = 'RUNNING', revision = revision + 1, updated_at = ?
                     WHERE job_ref = ? AND state = 'CLAIMED' AND owner_id = ? AND revision = ?
                       AND lease_expires_at > ?
                    """)) {
              update.setString(1, now.toString());
              update.setString(2, lease.job().jobRef().value());
              update.setString(3, lease.ownerId());
              update.setLong(4, lease.revision());
              update.setString(5, now.toString());
              if (update.executeUpdate() != 1) {
                return Optional.empty();
              }
              return Optional.of(lease.running());
            }
          });
    } catch (SQLException exception) {
      throw ProactiveRepositoryException.operationFailed(exception);
    }
  }

  @Override
  public boolean complete(
      ProactiveJobLease lease, ProactiveJobState terminalState, Instant completedAt) {
    Objects.requireNonNull(lease, "lease");
    Objects.requireNonNull(terminalState, "terminalState");
    Objects.requireNonNull(completedAt, "completedAt");
    if (!terminalState.terminal()) {
      throw new IllegalArgumentException("Proactive Job 只能提交终态");
    }
    try (var connection = schema.openConnection()) {
      return transaction(connection, () -> complete(connection, lease, terminalState, completedAt));
    } catch (SQLException exception) {
      throw ProactiveRepositoryException.operationFailed(exception);
    }
  }

  @Override
  public int recoverExpired(Instant now, int limit) {
    Objects.requireNonNull(now, "now");
    if (limit < 1 || limit > 256) {
      throw new IllegalArgumentException("Proactive recovery limit 必须在 1..256");
    }
    try (var connection = schema.openConnection()) {
      return transaction(connection, () -> recover(connection, now, limit));
    } catch (SQLException exception) {
      throw ProactiveRepositoryException.operationFailed(exception);
    }
  }

  private static Optional<ProactiveJobLease> claim(
      Connection connection, Instant now, String ownerId, Instant expiresAt) throws SQLException {
    try (var select =
        connection.prepareStatement(
            """
            SELECT job_ref, schedule_kind, next_run_at, every_millis, target_hash, idempotency_key,
                   state, attempts, max_attempts, revision
              FROM proactive_jobs
             WHERE state = 'SCHEDULED' AND attempts < max_attempts AND next_run_at <= ?
             ORDER BY next_run_at ASC, job_ref ASC
             LIMIT 1
            """)) {
      select.setString(1, now.toString());
      try (var rows = select.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        StoredJob stored = read(rows);
        try (var update =
            connection.prepareStatement(
                """
                UPDATE proactive_jobs
                   SET state = 'CLAIMED', attempts = attempts + 1, owner_id = ?,
                       lease_expires_at = ?, revision = revision + 1, updated_at = ?
                 WHERE job_ref = ? AND state = 'SCHEDULED' AND revision = ?
                """)) {
          update.setString(1, ownerId);
          update.setString(2, expiresAt.toString());
          update.setString(3, now.toString());
          update.setString(4, stored.job().jobRef().value());
          update.setLong(5, stored.revision());
          if (update.executeUpdate() != 1) {
            return Optional.empty();
          }
          ScheduledJob claimed =
              new ScheduledJob(
                  stored.job().jobRef(),
                  stored.job().schedule(),
                  stored.job().targetHash(),
                  stored.job().idempotencyKey(),
                  ProactiveJobState.CLAIMED,
                  stored.job().attempts() + 1,
                  stored.job().maxAttempts());
          return Optional.of(
              new ProactiveJobLease(claimed, ownerId, expiresAt, stored.revision() + 1));
        }
      }
    }
  }

  private static boolean complete(
      Connection connection,
      ProactiveJobLease lease,
      ProactiveJobState terminalState,
      Instant completedAt)
      throws SQLException {
    ScheduledJob job = lease.job();
    boolean periodic = job.schedule().kind() == ProactiveScheduleKind.EVERY;
    String nextRun = periodic ? nextFutureSlot(job.schedule(), completedAt).toString() : null;
    try (var update =
        connection.prepareStatement(
            """
            UPDATE proactive_jobs
               SET state = ?, next_run_at = COALESCE(?, next_run_at), attempts = ?,
                   owner_id = NULL, lease_expires_at = NULL, revision = revision + 1, updated_at = ?
             WHERE job_ref = ? AND state = 'RUNNING' AND owner_id = ? AND revision = ?
               AND lease_expires_at > ?
            """)) {
      update.setString(1, periodic ? ProactiveJobState.SCHEDULED.name() : terminalState.name());
      update.setString(2, nextRun);
      update.setInt(3, periodic ? 0 : job.attempts());
      update.setString(4, completedAt.toString());
      update.setString(5, job.jobRef().value());
      update.setString(6, lease.ownerId());
      update.setLong(7, lease.revision());
      update.setString(8, completedAt.toString());
      return update.executeUpdate() == 1;
    }
  }

  private static int recover(Connection connection, Instant now, int limit) throws SQLException {
    var expired = new ArrayList<StoredJob>();
    try (var select =
        connection.prepareStatement(
            """
            SELECT job_ref, schedule_kind, next_run_at, every_millis, target_hash, idempotency_key,
                   state, attempts, max_attempts, revision
              FROM proactive_jobs
             WHERE state IN ('CLAIMED', 'RUNNING') AND lease_expires_at <= ?
             ORDER BY lease_expires_at ASC, job_ref ASC
             LIMIT ?
            """)) {
      select.setString(1, now.toString());
      select.setInt(2, limit);
      try (var rows = select.executeQuery()) {
        while (rows.next()) {
          expired.add(read(rows));
        }
      }
    }
    int recovered = 0;
    for (StoredJob stored : expired) {
      ProactiveJobState state =
          stored.job().attempts() >= stored.job().maxAttempts()
              ? ProactiveJobState.FAILED
              : ProactiveJobState.SCHEDULED;
      try (var update =
          connection.prepareStatement(
              """
              UPDATE proactive_jobs
                 SET state = ?, owner_id = NULL, lease_expires_at = NULL,
                     revision = revision + 1, updated_at = ?
               WHERE job_ref = ? AND revision = ? AND state IN ('CLAIMED', 'RUNNING')
                 AND lease_expires_at <= ?
              """)) {
        update.setString(1, state.name());
        update.setString(2, now.toString());
        update.setString(3, stored.job().jobRef().value());
        update.setLong(4, stored.revision());
        update.setString(5, now.toString());
        recovered += update.executeUpdate();
      }
    }
    return recovered;
  }

  private static StoredJob read(java.sql.ResultSet rows) throws SQLException {
    ProactiveScheduleKind kind = ProactiveScheduleKind.valueOf(rows.getString("schedule_kind"));
    long storedEveryMillis = rows.getLong("every_millis");
    Long everyMillis = rows.wasNull() ? null : storedEveryMillis;
    var schedule =
        new ProactiveSchedule(
            kind,
            Instant.parse(rows.getString("next_run_at")),
            everyMillis == null ? null : Duration.ofMillis(everyMillis));
    var job =
        new ScheduledJob(
            ProactiveJobRef.parse(rows.getString("job_ref")),
            schedule,
            rows.getString("target_hash"),
            rows.getString("idempotency_key"),
            ProactiveJobState.parse(rows.getString("state")),
            rows.getInt("attempts"),
            rows.getInt("max_attempts"));
    return new StoredJob(job, rows.getLong("revision"));
  }

  private static void bindJob(java.sql.PreparedStatement insert, ScheduledJob job, Instant now)
      throws SQLException {
    insert.setString(1, job.jobRef().value());
    insert.setString(2, job.schedule().kind().name());
    insert.setString(3, job.schedule().nextRunAt().toString());
    if (job.schedule().every() == null) {
      insert.setNull(4, java.sql.Types.BIGINT);
    } else {
      insert.setLong(4, job.schedule().every().toMillis());
    }
    insert.setString(5, job.targetHash());
    insert.setString(6, job.idempotencyKey());
    insert.setString(7, job.state().name());
    insert.setInt(8, job.attempts());
    insert.setInt(9, job.maxAttempts());
    insert.setString(10, now.toString());
  }

  private static Instant nextFutureSlot(ProactiveSchedule schedule, Instant completedAt) {
    Instant next = schedule.nextRunAt();
    Duration interval = schedule.every();
    while (!next.isAfter(completedAt)) {
      next = next.plus(interval);
    }
    return next;
  }

  private static void validateLease(String ownerId, Duration leaseDuration) {
    if (ownerId == null || !ownerId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("Proactive ownerId 非法");
    }
    if (leaseDuration == null
        || leaseDuration.isNegative()
        || leaseDuration.isZero()
        || leaseDuration.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new IllegalArgumentException("Proactive lease 必须在 (0, 5m]");
    }
  }

  private static <T> T transaction(Connection connection, SqlWork<T> work) throws SQLException {
    connection.setAutoCommit(false);
    try {
      T result = work.run();
      connection.commit();
      return result;
    } catch (SQLException | RuntimeException exception) {
      try {
        connection.rollback();
      } catch (SQLException rollbackFailure) {
        exception.addSuppressed(rollbackFailure);
      }
      throw exception;
    } finally {
      connection.setAutoCommit(true);
    }
  }

  private record StoredJob(ScheduledJob job, long revision) {}

  @FunctionalInterface
  private interface SqlWork<T> {
    T run() throws SQLException;
  }
}
