package io.namei.agent.bootstrap.cli;

import io.namei.agent.kernel.channel.MessageContract;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class Utf8CliInput implements CliInput {
  public static final int MAX_LINE_BYTES = MessageContract.MAX_CONTENT_CHARACTERS * 4;
  private final InputStream input;

  public Utf8CliInput(InputStream input) {
    this.input = Objects.requireNonNull(input, "input");
  }

  @Override
  public synchronized String readLine() {
    var bytes = new ByteArrayOutputStream();
    boolean reachedEof = false;
    try {
      while (true) {
        int next = input.read();
        if (next == -1) {
          reachedEof = true;
          break;
        }
        if (next == '\n') {
          break;
        }
        if (bytes.size() >= MAX_LINE_BYTES) {
          throw new CliInputException("CLI 输入行超过字节上限");
        }
        bytes.write(next);
      }
    } catch (IOException failure) {
      throw new CliInputException("CLI stdin 读取失败", failure);
    }
    if (reachedEof && bytes.size() == 0) {
      return null;
    }

    byte[] encoded = bytes.toByteArray();
    int length = encoded.length;
    if (length > 0 && encoded[length - 1] == '\r') {
      length--;
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(encoded, 0, length))
          .toString();
    } catch (CharacterCodingException failure) {
      throw new CliInputException("CLI stdin 不是有效 UTF-8", failure);
    }
  }
}
