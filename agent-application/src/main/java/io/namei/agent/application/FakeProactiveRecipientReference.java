package io.namei.agent.application;

import java.util.Objects;
import java.util.regex.Pattern;

/** Test-only recipient binding; never a channel ID, URL, or public API parameter. */
record FakeProactiveRecipientReference(String value) {
  private static final Pattern FORMAT = Pattern.compile("fake-recipient-[a-z0-9-]{1,48}");

  FakeProactiveRecipientReference {
    value = Objects.requireNonNull(value, "value");
    if (!FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("Fake Recipient 引用格式无效");
    }
  }

  static FakeProactiveRecipientReference of(String value) {
    return new FakeProactiveRecipientReference(value);
  }

  @Override
  public String toString() {
    return "FakeProactiveRecipientReference[value=<redacted>]";
  }
}
