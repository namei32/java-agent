package io.namei.agent.bootstrap.control;

import java.security.SecureRandom;

@FunctionalInterface
public interface ControlRandomSource {
  byte[] nextBytes(int size);

  static ControlRandomSource secure() {
    SecureRandom random = new SecureRandom();
    return size -> {
      if (size < 1 || size > 64) {
        throw new IllegalArgumentException("控制面随机值长度无效");
      }
      byte[] value = new byte[size];
      random.nextBytes(value);
      return value;
    };
  }
}
