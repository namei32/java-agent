package io.namei.agent.application;

import java.util.Objects;
import java.util.regex.Pattern;

/** Opaque, local-only reference for a proactive Fake Delivery operation. */
record ProactiveDeliveryOperationReference(String value) {
  private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{22}");

  ProactiveDeliveryOperationReference {
    value = Objects.requireNonNull(value, "value");
    if (!FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("主动投递操作引用格式无效");
    }
  }

  static ProactiveDeliveryOperationReference of(String value) {
    return new ProactiveDeliveryOperationReference(value);
  }

  @Override
  public String toString() {
    return "ProactiveDeliveryOperationReference[value=<redacted>]";
  }
}

@FunctionalInterface
interface ProactiveDeliveryOperationReferenceGenerator {
  ProactiveDeliveryOperationReference next();
}
