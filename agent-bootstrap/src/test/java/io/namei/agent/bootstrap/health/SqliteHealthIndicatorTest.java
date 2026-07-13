package io.namei.agent.bootstrap.health;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.adapter.sqlite.JdbcSessionRepository;
import io.namei.agent.adapter.sqlite.SqliteSchemaInitializer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Status;

class SqliteHealthIndicatorTest {
  @TempDir Path tempDir;

  @Test
  void reportsUpForAvailableDatabase() {
    var schema = new SqliteSchemaInitializer(tempDir.resolve("sessions.db"), 5_000);
    schema.initialize();

    var health = new SqliteHealthIndicator(new JdbcSessionRepository(schema)).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void reportsDownWhenDatabaseCannotBeOpened() {
    var unavailable =
        new JdbcSessionRepository(
            new SqliteSchemaInitializer(tempDir.resolve("missing/parent/sessions.db"), 5_000));

    var health = new SqliteHealthIndicator(unavailable).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }
}
