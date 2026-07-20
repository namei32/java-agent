package io.namei.agent.application;

import java.util.Objects;
import java.util.regex.Pattern;

/** Opaque local-only reference for one approved proactive Java-native Memory capture. */
record ProactiveMemoryOperationReference(String value) {
  private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{22}");

  ProactiveMemoryOperationReference {
    value = Objects.requireNonNull(value, "value");
    if (!FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("主动记忆操作引用格式无效");
    }
  }

  static ProactiveMemoryOperationReference of(String value) {
    return new ProactiveMemoryOperationReference(value);
  }

  @Override
  public String toString() {
    return "ProactiveMemoryOperationReference[value=<redacted>]";
  }
}

@FunctionalInterface
interface ProactiveMemoryOperationReferenceGenerator {
  ProactiveMemoryOperationReference next();
}
