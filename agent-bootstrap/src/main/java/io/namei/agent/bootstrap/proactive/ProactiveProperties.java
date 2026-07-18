package io.namei.agent.bootstrap.proactive;

import io.namei.agent.kernel.proactive.ProactiveJobRef;
import io.namei.agent.kernel.proactive.ProactiveJobState;
import io.namei.agent.kernel.proactive.ProactiveSchedule;
import io.namei.agent.kernel.proactive.ProactiveScheduleKind;
import io.namei.agent.kernel.proactive.ScheduledJob;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.proactive")
public final class ProactiveProperties {
  private final ProactiveMode mode;
  private final String ownerId;
  private final Duration leaseDuration;
  private final Duration idleWait;
  private final Duration shutdownTimeout;
  private final Duration cooldown;
  private final List<Plan> plans;

  @ConstructorBinding
  public ProactiveProperties(
      @DefaultValue("DISABLED") String mode,
      @DefaultValue("proactive-local") String ownerId,
      @DefaultValue("30s") Duration leaseDuration,
      @DefaultValue("5s") Duration idleWait,
      @DefaultValue("2s") Duration shutdownTimeout,
      @DefaultValue("5m") Duration cooldown,
      List<Plan> plans) {
    this.mode = parseMode(mode);
    this.ownerId = ownerId == null ? "proactive-local" : ownerId;
    this.leaseDuration = leaseDuration == null ? Duration.ofSeconds(30) : leaseDuration;
    this.idleWait = idleWait == null ? Duration.ofSeconds(5) : idleWait;
    this.shutdownTimeout = shutdownTimeout == null ? Duration.ofSeconds(2) : shutdownTimeout;
    this.cooldown = cooldown == null ? Duration.ofMinutes(5) : cooldown;
    this.plans = List.copyOf(plans == null ? List.of() : plans);
    new io.namei.agent.application.ProactiveSchedulerSettings(
        this.ownerId, this.leaseDuration, this.idleWait, 32, this.shutdownTimeout);
    if (this.cooldown.isZero()
        || this.cooldown.isNegative()
        || this.cooldown.compareTo(Duration.ofDays(365)) > 0) {
      throw new IllegalArgumentException("agent.proactive.cooldown 必须为有界正数");
    }
    if (this.mode == ProactiveMode.LOCAL_SQLITE && this.plans.isEmpty()) {
      throw new IllegalArgumentException("LOCAL_SQLITE 至少需要一个 hash-only allowlisted plan");
    }
    if (new HashSet<>(this.plans.stream().map(Plan::jobRef).toList()).size() != this.plans.size()
        || new HashSet<>(this.plans.stream().map(Plan::idempotencyKey).toList()).size()
            != this.plans.size()) {
      throw new IllegalArgumentException("agent.proactive.plans 的 job-ref 与 idempotency-key 不能重复");
    }
  }

  public ProactiveMode mode() {
    return mode;
  }

  public String ownerId() {
    return ownerId;
  }

  public Duration leaseDuration() {
    return leaseDuration;
  }

  public Duration idleWait() {
    return idleWait;
  }

  public Duration shutdownTimeout() {
    return shutdownTimeout;
  }

  public Duration cooldown() {
    return cooldown;
  }

  public List<Plan> plans() {
    return plans;
  }

  @Override
  public String toString() {
    return "ProactiveProperties[mode=" + mode + ", plans=<configured>]";
  }

  private static ProactiveMode parseMode(String value) {
    if (value == null) {
      return ProactiveMode.DISABLED;
    }
    try {
      ProactiveMode parsed = ProactiveMode.valueOf(value);
      if (!parsed.name().equals(value)) {
        throw new IllegalArgumentException();
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("agent.proactive.mode 仅接受 DISABLED、LOCAL_SQLITE");
    }
  }

  public record Plan(
      String jobRef,
      String scheduleKind,
      Instant nextRunAt,
      Duration every,
      String targetHash,
      String idempotencyKey,
      int maxAttempts) {
    public Plan {
      ProactiveJobRef.parse(jobRef);
      Objects.requireNonNull(nextRunAt, "nextRunAt");
      ProactiveScheduleKind.valueOf(Objects.requireNonNull(scheduleKind, "scheduleKind"));
      new ScheduledJob(
          ProactiveJobRef.parse(jobRef),
          new ProactiveSchedule(ProactiveScheduleKind.valueOf(scheduleKind), nextRunAt, every),
          targetHash,
          idempotencyKey,
          ProactiveJobState.SCHEDULED,
          0,
          maxAttempts);
    }

    ScheduledJob toJob() {
      return new ScheduledJob(
          ProactiveJobRef.parse(jobRef),
          new ProactiveSchedule(ProactiveScheduleKind.valueOf(scheduleKind), nextRunAt, every),
          targetHash,
          idempotencyKey,
          ProactiveJobState.SCHEDULED,
          0,
          maxAttempts);
    }
  }
}
