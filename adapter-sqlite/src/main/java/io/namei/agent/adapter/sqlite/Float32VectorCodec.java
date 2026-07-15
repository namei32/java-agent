package io.namei.agent.adapter.sqlite;

import io.namei.agent.kernel.memory.EmbeddingVector;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

public final class Float32VectorCodec {
  public byte[] encode(EmbeddingVector vector) {
    Objects.requireNonNull(vector, "vector");
    float[] values = vector.values();
    var buffer = ByteBuffer.allocate(Math.multiplyExact(values.length, Float.BYTES));
    buffer.order(ByteOrder.LITTLE_ENDIAN);
    for (float value : values) {
      buffer.putFloat(value);
    }
    return buffer.array();
  }

  public EmbeddingVector decode(byte[] encoded, int dimensions) {
    Objects.requireNonNull(encoded, "encoded");
    if (dimensions < 1 || dimensions > 4096) {
      throw new IllegalArgumentException("Embedding 维度必须在 1..4096");
    }
    int expectedLength = Math.multiplyExact(dimensions, Float.BYTES);
    if (encoded.length != expectedLength) {
      throw new IllegalArgumentException("Embedding BLOB 长度与维度不一致");
    }
    var buffer = ByteBuffer.wrap(encoded.clone()).order(ByteOrder.LITTLE_ENDIAN);
    var values = new float[dimensions];
    for (int index = 0; index < dimensions; index++) {
      values[index] = buffer.getFloat();
    }
    return new EmbeddingVector(values);
  }
}
