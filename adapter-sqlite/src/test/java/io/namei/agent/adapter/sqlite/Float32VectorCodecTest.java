package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.memory.EmbeddingVector;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class Float32VectorCodecTest {
  private final Float32VectorCodec codec = new Float32VectorCodec();

  @Test
  void matchesTheJavaContractLittleEndianFixtureAndRoundTrips() {
    var vector = new EmbeddingVector(new float[] {1.0f, -2.5f, 0.25f, 0.0f});

    byte[] encoded = codec.encode(vector);

    assertThat(HexFormat.of().formatHex(encoded)).isEqualTo("0000803f000020c00000803e00000000");
    assertThat(codec.decode(encoded, 4)).isEqualTo(vector);
  }

  @Test
  void returnsDefensiveBinaryAndVectorCopies() {
    var vector = new EmbeddingVector(new float[] {1.0f, 2.0f});
    byte[] first = codec.encode(vector);
    first[0] = 0x7f;

    byte[] source = codec.encode(vector);
    var decoded = codec.decode(source, 2);
    source[0] = 0x7f;

    assertThat(codec.encode(vector)).containsExactly(0, 0, -128, 63, 0, 0, 0, 64);
    assertThat(decoded.values()).containsExactly(1.0f, 2.0f);
  }

  @Test
  void rejectsInvalidLengthDimensionsAndNumericValues() {
    assertThatThrownBy(() -> codec.decode(new byte[4], 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> codec.decode(new byte[4], 4097))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> codec.decode(new byte[3], 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> codec.decode(new byte[4], 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> codec.decode(new byte[4], 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("范数");
    assertThatThrownBy(() -> codec.decode(floatBlob(Float.NaN), 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("有限值");
    assertThatThrownBy(() -> codec.decode(floatBlob(Float.POSITIVE_INFINITY), 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("有限值");
  }

  private static byte[] floatBlob(float value) {
    return ByteBuffer.allocate(Float.BYTES).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array();
  }
}
