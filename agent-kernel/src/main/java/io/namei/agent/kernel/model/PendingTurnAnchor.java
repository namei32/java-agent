package io.namei.agent.kernel.model;

import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Versioned Session-side ordering anchor for a pending Tool operation.
 *
 * <p>It contains no Tool arguments, approval binding, result or conversation content. The operation
 * reference is opaque and must never be rendered through {@link #toString()}.
 */
public record PendingTurnAnchor(
    int anchorVersion,
    String operationReference,
    String sessionId,
    long createdNextSequence,
    long resumeNextSequence,
    PendingTurnAnchorState state,
    String projectionVersion) {
  public static final int VERSION = 1;
  private static final Pattern BASE64_URL_128_BIT = Pattern.compile("[A-Za-z0-9_-]{22}");
  private static final Pattern PROJECTION_VERSION = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  public PendingTurnAnchor {
    if (anchorVersion != VERSION) {
      throw new IllegalArgumentException("不支持的 Pending Turn Anchor 版本");
    }
    operationReference = requireOpaqueReference(operationReference);
    sessionId = required(sessionId, "Session ID");
    if (sessionId.length() > 256) {
      throw new IllegalArgumentException("Session ID 超出 Anchor 上限");
    }
    if (createdNextSequence < 0) {
      throw new IllegalArgumentException("Anchor 创建序号不能为负数");
    }
    long requiredResumeSequence;
    try {
      requiredResumeSequence = Math.addExact(createdNextSequence, 2);
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("Anchor 序号已耗尽", exception);
    }
    if (resumeNextSequence != requiredResumeSequence) {
      throw new IllegalArgumentException("Anchor 恢复序号必须紧随安全 Pending Turn");
    }
    try {
      Math.addExact(resumeNextSequence, 1);
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("Anchor 恢复序号没有可用完成位置", exception);
    }
    state = Objects.requireNonNull(state, "state");
    projectionVersion = required(projectionVersion, "投影版本");
    if (!PROJECTION_VERSION.matcher(projectionVersion).matches()) {
      throw new IllegalArgumentException("Anchor 投影版本格式无效");
    }
  }

  public static PendingTurnAnchor pending(
      String operationReference,
      String sessionId,
      long createdNextSequence,
      String projectionVersion) {
    long resumeNextSequence;
    try {
      resumeNextSequence = Math.addExact(createdNextSequence, 2);
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("Anchor 序号已耗尽", exception);
    }
    return new PendingTurnAnchor(
        VERSION,
        operationReference,
        sessionId,
        createdNextSequence,
        resumeNextSequence,
        PendingTurnAnchorState.PENDING_APPROVAL,
        projectionVersion);
  }

  public PendingTurnAnchor transitionTo(PendingTurnAnchorState next) {
    Objects.requireNonNull(next, "next");
    if (state != PendingTurnAnchorState.PENDING_APPROVAL || !next.isTerminal()) {
      throw new IllegalStateException("不允许的 Pending Turn Anchor 状态转换: " + state + " -> " + next);
    }
    return new PendingTurnAnchor(
        anchorVersion,
        operationReference,
        sessionId,
        createdNextSequence,
        resumeNextSequence,
        next,
        projectionVersion);
  }

  public boolean isTerminal() {
    return state.isTerminal();
  }

  @Override
  public String toString() {
    return "PendingTurnAnchor[anchorVersion="
        + anchorVersion
        + ", operationReference=<redacted>, sessionId=<redacted>, createdNextSequence="
        + createdNextSequence
        + ", resumeNextSequence="
        + resumeNextSequence
        + ", state="
        + state
        + ", projectionVersion="
        + projectionVersion
        + "]";
  }

  private static String requireOpaqueReference(String value) {
    String normalized = required(value, "Operation Ref");
    if (!BASE64_URL_128_BIT.matcher(normalized).matches()) {
      throw new IllegalArgumentException("Anchor Operation Ref 格式无效");
    }
    try {
      if (Base64.getUrlDecoder().decode(normalized).length != 16) {
        throw new IllegalArgumentException("Anchor Operation Ref 长度无效");
      }
    } catch (IllegalArgumentException invalidReference) {
      throw new IllegalArgumentException("Anchor Operation Ref 格式无效");
    }
    return normalized;
  }

  private static String required(String value, String field) {
    String normalized = Objects.requireNonNull(value, field).strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return normalized;
  }
}
