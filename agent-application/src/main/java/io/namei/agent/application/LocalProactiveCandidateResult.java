package io.namei.agent.application;

import io.namei.agent.kernel.proactive.ProactiveDeliveryBoundary;
import io.namei.agent.kernel.proactive.ProactiveStableCode;
import java.util.Objects;
import java.util.Optional;

/**
 * Safe public outcome of P2-A. It cannot expose a candidate's Source text or authorize delivery.
 */
public final class LocalProactiveCandidateResult {
  public enum Kind {
    CANDIDATE_READY,
    SKIPPED,
    CANCELLED
  }

  private final Kind kind;
  private final Optional<ProactiveStableCode> code;
  private final LocalProactiveCandidate candidate;

  private LocalProactiveCandidateResult(
      Kind kind, Optional<ProactiveStableCode> code, LocalProactiveCandidate candidate) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.code = Objects.requireNonNull(code, "code");
    this.candidate = candidate;
    if ((kind == Kind.SKIPPED) != code.isPresent()) {
      throw new IllegalArgumentException("候选准备状态与稳定码不匹配");
    }
    if ((kind == Kind.CANDIDATE_READY) != (candidate != null)) {
      throw new IllegalArgumentException("候选准备状态与候选不匹配");
    }
    if (kind == Kind.CANCELLED && candidate != null) {
      throw new IllegalArgumentException("已取消的候选准备不能保留候选");
    }
  }

  static LocalProactiveCandidateResult candidateReady(LocalProactiveCandidate candidate) {
    return new LocalProactiveCandidateResult(
        Kind.CANDIDATE_READY, Optional.empty(), Objects.requireNonNull(candidate, "candidate"));
  }

  public static LocalProactiveCandidateResult skipped(ProactiveStableCode code) {
    return new LocalProactiveCandidateResult(
        Kind.SKIPPED, Optional.of(Objects.requireNonNull(code, "code")), null);
  }

  public static LocalProactiveCandidateResult cancelled() {
    return new LocalProactiveCandidateResult(Kind.CANCELLED, Optional.empty(), null);
  }

  public Kind kind() {
    return kind;
  }

  public Optional<ProactiveStableCode> code() {
    return code;
  }

  public boolean hasCandidate() {
    return candidate != null;
  }

  /** P2-B may consume the ephemeral candidate only from the same application package. */
  Optional<LocalProactiveCandidate> candidateForPreparation() {
    return Optional.ofNullable(candidate);
  }

  /** P2-A has neither an Outbox nor a Transport authorization. */
  public ProactiveDeliveryBoundary deliveryBoundary() {
    return new ProactiveDeliveryBoundary(ProactiveDeliveryBoundary.Disposition.NOT_REQUESTED);
  }

  /** P2-A has no Memory port. */
  public int memoryMutationCount() {
    return 0;
  }

  @Override
  public String toString() {
    return "LocalProactiveCandidateResult[kind="
        + kind
        + ", code="
        + code
        + ", candidate="
        + (candidate == null ? "absent" : "<redacted>")
        + "]";
  }
}
