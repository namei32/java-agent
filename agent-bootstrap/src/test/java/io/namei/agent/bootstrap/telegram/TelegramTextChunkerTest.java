package io.namei.agent.bootstrap.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TelegramTextChunkerTest {
  private final TelegramTextChunker chunker = new TelegramTextChunker();

  @Test
  void keepsTextAtOrBelowTheConservativeLimitInOneChunk() {
    assertThat(chunker.split("回答")).containsExactly("回答");
    assertThat(chunker.split("x".repeat(4000))).containsExactly("x".repeat(4000));
  }

  @Test
  void splitsByUtf16UnitsWithoutChangingTextOrAddingMarkers() {
    String text = "a".repeat(4000) + "\n" + "b".repeat(4000);

    assertThat(chunker.split(text)).containsExactly("a".repeat(4000), "\n" + "b".repeat(3999), "b");
    assertThat(String.join("", chunker.split(text))).isEqualTo(text);
  }

  @Test
  void neverCutsASurrogatePairAtTheBoundary() {
    String text = "a".repeat(3999) + "😊" + "b";

    assertThat(chunker.split(text)).containsExactly("a".repeat(3999), "😊b");
    assertThat(chunker.split(text))
        .allSatisfy(
            part -> {
              assertThat(part.length()).isLessThanOrEqualTo(4000);
              assertThat(Character.isHighSurrogate(part.charAt(part.length() - 1))).isFalse();
            });
  }

  @Test
  void rejectsNullOrEmptyTextInsteadOfCreatingAnInvalidTelegramCall() {
    assertThatThrownBy(() -> chunker.split(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> chunker.split("")).isInstanceOf(IllegalArgumentException.class);
  }
}
