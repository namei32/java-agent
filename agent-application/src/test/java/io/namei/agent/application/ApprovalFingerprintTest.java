package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.tool.ToolRisk;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApprovalFingerprintTest {
  private static final Instant ISSUED_AT = Instant.parse("2026-07-14T05:00:00Z");
  private static final Instant EXPIRES_AT = Instant.parse("2026-07-14T05:05:00Z");

  @Test
  void canonicalizesObjectKeysButPreservesArraysStringsAndNumberKinds() {
    var first = new LinkedHashMap<String, Object>();
    first.put("z", List.of("alpha", 1, new BigDecimal("1.0")));
    first.put("a", Map.of("enabled", true));
    var second = new LinkedHashMap<String, Object>();
    second.put("a", Map.of("enabled", true));
    second.put("z", List.of("alpha", 1, new BigDecimal("1.0")));

    assertThat(ApprovalFingerprint.argumentsHash(first))
        .isEqualTo(ApprovalFingerprint.argumentsHash(second));
    assertThat(ApprovalFingerprint.argumentsHash(Map.of("value", List.of(1, 2))))
        .isNotEqualTo(ApprovalFingerprint.argumentsHash(Map.of("value", List.of(2, 1))));
    assertThat(ApprovalFingerprint.argumentsHash(Map.of("value", "1")))
        .isNotEqualTo(ApprovalFingerprint.argumentsHash(Map.of("value", 1)));
    assertThat(ApprovalFingerprint.argumentsHash(Map.of("value", 1)))
        .isNotEqualTo(
            ApprovalFingerprint.argumentsHash(Map.of("value", new BigDecimal("1.0"))));
  }

  @Test
  void canonicalizesJsonNullTheSameFromMapAndRawJson() {
    var arguments = new LinkedHashMap<String, Object>();
    arguments.put("optional", null);

    assertThat(ApprovalFingerprint.argumentsHash(arguments))
        .isEqualTo(ApprovalFingerprint.argumentsHashJson("{\"optional\":null}"));
  }

  @Test
  void bindsEveryOperationFieldWithLengthPrefixes() {
    var base = fingerprint("ab", "c", "call-1", "tool", "v1", ToolRisk.WRITE);

    assertThat(base)
        .isNotEqualTo(fingerprint("a", "bc", "call-1", "tool", "v1", ToolRisk.WRITE));
    assertThat(base)
        .isNotEqualTo(fingerprint("ab", "c2", "call-1", "tool", "v1", ToolRisk.WRITE));
    assertThat(base)
        .isNotEqualTo(fingerprint("ab", "c", "call-2", "tool", "v1", ToolRisk.WRITE));
    assertThat(base)
        .isNotEqualTo(fingerprint("ab", "c", "call-1", "other", "v1", ToolRisk.WRITE));
    assertThat(base)
        .isNotEqualTo(fingerprint("ab", "c", "call-1", "tool", "v2", ToolRisk.WRITE));
    assertThat(base)
        .isNotEqualTo(
            fingerprint(
                "ab", "c", "call-1", "tool", "v1", ToolRisk.EXTERNAL_SIDE_EFFECT));
  }

  @Test
  void failsClosedForDuplicateFieldsNonFiniteNumbersAndUnstableValuesWithoutLeakingInputs() {
    String privateValue = "private-token-should-not-leak";

    assertThatThrownBy(
            () ->
                ApprovalFingerprint.argumentsHashJson(
                    "{\"secret\":\"" + privateValue + "\",\"secret\":\"other\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(privateValue);
    assertThatThrownBy(
            () -> ApprovalFingerprint.argumentsHash(Map.of("secret", privateValue, "value", 0.0d / 0.0d)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(privateValue);
    assertThatThrownBy(
            () ->
                ApprovalFingerprint.argumentsHash(
                    Map.of("secret", privateValue, "unstable", new Object())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(privateValue);
  }

  private static String fingerprint(
      String session,
      String turn,
      String call,
      String tool,
      String version,
      ToolRisk risk) {
    return ApprovalFingerprint.calculate(
        session,
        turn,
        call,
        tool,
        version,
        risk,
        ApprovalFingerprint.argumentsHash(Map.of("value", "fixed")),
        "idempotency-1",
        ISSUED_AT,
        EXPIRES_AT);
  }
}
