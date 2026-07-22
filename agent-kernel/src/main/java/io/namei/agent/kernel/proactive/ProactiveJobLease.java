package io.namei.agent.kernel.proactive;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** 已持久化的所有权证明；Worker 提交结果前必须提供它。 */
public record ProactiveJobLease(
    ScheduledJob job, String ownerId, Instant expiresAt, long revision) {
  private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  public ProactiveJobLease {
    job = Objects.requireNonNull(job, "job");
    expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    if (ownerId == null || !OWNER.matcher(ownerId).matches() || revision < 1) {
      throw ProactiveContract.violation(ProactiveStableCode.PROACTIVE_CONTRACT_INVALID);
    }
    if (job.state() != ProactiveJobState.CLAIMED && job.state() != ProactiveJobState.RUNNING) {
      throw ProactiveContract.violation(ProactiveStableCode.PROACTIVE_CONTRACT_INVALID);
    }
  }

  public ProactiveJobLease running() {
    return new ProactiveJobLease(
        new ScheduledJob(
            job.jobRef(),
            job.schedule(),
            job.targetHash(),
            job.idempotencyKey(),
            ProactiveJobState.RUNNING,
            job.attempts(),
            job.maxAttempts()),
        ownerId,
        expiresAt,
        revision + 1);
  }
}
