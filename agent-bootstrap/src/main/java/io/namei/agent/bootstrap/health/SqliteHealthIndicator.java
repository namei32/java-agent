package io.namei.agent.bootstrap.health;

import io.namei.agent.adapter.sqlite.JdbcSessionRepository;
import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

public final class SqliteHealthIndicator implements HealthIndicator {
  private final JdbcSessionRepository repository;

  public SqliteHealthIndicator(JdbcSessionRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  @Override
  public Health health() {
    return repository.isAvailable() ? Health.up().build() : Health.down().build();
  }
}
