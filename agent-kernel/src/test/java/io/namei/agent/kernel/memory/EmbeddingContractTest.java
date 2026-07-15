package io.namei.agent.kernel.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.port.EmbeddingPort;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbeddingContractTest {
  @Test
  void copiesRequestsVectorsAndResultsWithoutExposingMutableArrays() {
    var texts = new ArrayList<>(List.of("第一条", " 第二条 "));
    var request = new EmbeddingRequest(texts);
    texts.set(0, "已篡改");

    float[] source = {1.0f, -2.5f};
    var vector = new EmbeddingVector(source);
    source[0] = 99.0f;
    float[] exposed = vector.values();
    exposed[1] = 99.0f;

    var vectors = new ArrayList<>(List.of(vector));
    var result = new EmbeddingResult(" embedding-model ", 2, vectors);
    vectors.clear();
    EmbeddingPort port = ignored -> result;

    assertThat(request.texts()).containsExactly("第一条", " 第二条 ");
    assertThatThrownBy(() -> request.texts().add("第三条"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(vector.values()).containsExactly(1.0f, -2.5f);
    assertThat(vector.dimensions()).isEqualTo(2);
    assertThat(vector.toString()).isEqualTo("EmbeddingVector[dimensions=2]");
    assertThat(port.embed(request).model()).isEqualTo("embedding-model");
    assertThat(result.vectors()).containsExactly(vector);
    assertThatThrownBy(() -> result.vectors().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void allowsAnEmptyBatchButRejectsInvalidTextsAndVectors() {
    assertThat(new EmbeddingRequest(List.of()).texts()).isEmpty();
    assertThat(new EmbeddingResult("embedding-model", 2, List.of()).vectors()).isEmpty();

    assertThatThrownBy(() -> new EmbeddingRequest(List.of(" ")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmbeddingRequest(java.util.Arrays.asList("ok", null)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new EmbeddingVector(new float[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmbeddingVector(new float[4097]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmbeddingVector(new float[] {0.0f, -0.0f}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmbeddingVector(new float[] {Float.NaN}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmbeddingVector(new float[] {Float.POSITIVE_INFINITY}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fixesLogicalModelDimensionsAndVectorShape() {
    var vector = new EmbeddingVector(new float[] {1.0f, 0.0f});

    assertThatThrownBy(() -> new EmbeddingResult(" ", 2, List.of(vector)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmbeddingResult("model", 0, List.of(vector)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmbeddingResult("model", 4097, List.of(vector)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmbeddingResult("model", 3, List.of(vector)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
