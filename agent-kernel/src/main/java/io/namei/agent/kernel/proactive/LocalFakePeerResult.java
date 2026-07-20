package io.namei.agent.kernel.proactive;

import java.util.Objects;

/**
 * A bounded, sanitized terminal projection returned by the injected P4 Fake Port. It never
 * preserves stdout, stderr, a process identifier, or a command.
 */
public final class LocalFakePeerResult {
  private final PeerTaskState state;
  private final String safeOutput;

  private LocalFakePeerResult(PeerTaskState state, String safeOutput) {
    this.state = Objects.requireNonNull(state, "state");
    this.safeOutput = Objects.requireNonNull(safeOutput, "safeOutput");
    if (!state.terminal()) {
      throw ProactiveContract.violation(ProactiveStableCode.PEER_CONTRACT_INVALID);
    }
  }

  public static LocalFakePeerResult terminal(PeerTaskState state, String output) {
    Objects.requireNonNull(output, "output");
    String sanitized = sanitize(output);
    if (sanitized.codePointCount(0, sanitized.length())
        > LocalFakePeerResourceBudget.FIXED_MAX_OUTPUT_CODE_POINTS) {
      throw ProactiveContract.violation(ProactiveStableCode.PEER_CONTRACT_INVALID);
    }
    return new LocalFakePeerResult(state, sanitized);
  }

  public PeerTaskState state() {
    return state;
  }

  public String safeOutput() {
    return safeOutput;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof LocalFakePeerResult result)) {
      return false;
    }
    return state == result.state && safeOutput.equals(result.safeOutput);
  }

  @Override
  public int hashCode() {
    return Objects.hash(state, safeOutput);
  }

  private static String sanitize(String output) {
    String normalized = output.replace("\r\n", "\n").replace('\r', '\n');
    StringBuilder sanitized = new StringBuilder(normalized.length());
    normalized
        .codePoints()
        .filter(codePoint -> codePoint >= 0x20 || codePoint == '\n')
        .forEach(sanitized::appendCodePoint);
    return sanitized.toString();
  }

  @Override
  public String toString() {
    return "LocalFakePeerResult[state=" + state + ", safeOutput=<redacted>]";
  }
}
