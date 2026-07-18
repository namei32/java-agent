package io.namei.agent.kernel.proactive;

import java.util.regex.Pattern;

public record ProactiveJobRef(String value) {
  private static final Pattern VALID = Pattern.compile("[a-z][a-z0-9-]{0,62}");

  public ProactiveJobRef {
    if (value == null || !VALID.matcher(value).matches()) {
      throw ProactiveContract.violation(ProactiveStableCode.PROACTIVE_CONTRACT_INVALID);
    }
  }

  public static ProactiveJobRef parse(String value) {
    return new ProactiveJobRef(value);
  }

  @Override
  public String toString() {
    return "ProactiveJobRef[<redacted>]";
  }
}
