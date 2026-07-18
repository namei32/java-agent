package io.namei.agent.bootstrap.control;

import io.namei.agent.kernel.control.ControlStableCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

public final class ControlPlaneAudit {
  private final Clock clock;
  private final ControlPlaneAuditSink sink;

  public ControlPlaneAudit(Clock clock, ControlPlaneAuditSink sink) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sink = Objects.requireNonNull(sink, "sink");
  }

  public static ControlPlaneAudit disabled() {
    return new ControlPlaneAudit(Clock.systemUTC(), ControlPlaneAuditSink.disabled());
  }

  public void record(
      String action,
      String result,
      ControlStableCode code,
      String requestId,
      String actorRef,
      String turnRef,
      long count,
      long durationMillis) {
    try {
      sink.accept(
          new ControlAuditEvent(
              clock.instant(),
              requestId,
              action,
              result,
              code == null ? "" : code.name(),
              hash("actor", actorRef),
              hash("turn", turnRef),
              count,
              durationMillis));
    } catch (RuntimeException ignored) {
      // 审计失败不能改变认证、控制 API 或 Agent 主链路。
    }
  }

  private static String hash(String domain, String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(domain.getBytes(StandardCharsets.US_ASCII));
      digest.update((byte) 0);
      byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      String encoded =
          Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hashed, 16));
      Arrays.fill(hashed, (byte) 0);
      return encoded;
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JDK 缺少 SHA-256");
    }
  }
}
