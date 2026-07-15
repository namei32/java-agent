package io.namei.agent.adapter.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.error.EmbeddingInvocationException;
import io.namei.agent.kernel.error.InvalidEmbeddingResponseException;
import io.namei.agent.kernel.memory.EmbeddingRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

class SpringAiEmbeddingAdapterTest {
  @Test
  void skipsEmptyBatchesEnforcesTenAndRestoresProviderIndexOrder() {
    var options = options(2);
    var model =
        new StubEmbeddingModel(
            request -> {
              var embeddings =
                  new ArrayList<>(
                      IntStream.range(0, request.getInstructions().size())
                          .mapToObj(index -> new Embedding(new float[] {index + 1.0f, 1.0f}, index))
                          .toList());
              Collections.reverse(embeddings);
              var metadata = new EmbeddingResponseMetadata();
              metadata.setModel("provider-returned-alias");
              return new EmbeddingResponse(embeddings, metadata);
            });
    var adapter = new SpringAiEmbeddingAdapter(model, options);

    var empty = adapter.embed(new EmbeddingRequest(List.of()));

    assertThat(empty.model()).isEqualTo("provider-model");
    assertThat(empty.dimensions()).isEqualTo(2);
    assertThat(empty.vectors()).isEmpty();
    assertThat(model.calls()).isZero();

    var texts = IntStream.range(0, 10).mapToObj(index -> "  text-" + index + "  ").toList();
    var result = adapter.embed(new EmbeddingRequest(texts));

    assertThat(model.calls()).isEqualTo(1);
    assertThat(model.lastRequest().getOptions()).isSameAs(options);
    assertThat(model.lastRequest().getInstructions())
        .containsExactlyElementsOf(
            IntStream.range(0, 10).mapToObj(index -> "text-" + index).toList());
    assertThat(result.model()).isEqualTo("provider-model");
    assertThat(result.dimensions()).isEqualTo(2);
    assertThat(result.vectors().getFirst().values()).containsExactly(1.0f, 1.0f);
    assertThat(result.vectors().getLast().values()).containsExactly(10.0f, 1.0f);

    assertThatThrownBy(
            () ->
                adapter.embed(
                    new EmbeddingRequest(
                        IntStream.range(0, 11).mapToObj(index -> "text-" + index).toList())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Embedding 单批不能超过 10 条");
    assertThat(model.calls()).isEqualTo(1);
  }

  @Test
  void stripsAndTruncatesAtUnicodeCodePointBoundary() {
    var model =
        new StubEmbeddingModel(
            request -> new EmbeddingResponse(List.of(new Embedding(new float[] {1.0f, 0.0f}, 0))));
    var adapter = new SpringAiEmbeddingAdapter(model, options(2));
    String text = "  " + "a".repeat(1999) + "😀" + "Z  ";

    adapter.embed(new EmbeddingRequest(List.of(text)));

    String sent = model.lastRequest().getInstructions().getFirst();
    assertThat(sent.codePointCount(0, sent.length())).isEqualTo(2000);
    assertThat(sent).endsWith("😀").doesNotContain("Z");
    assertThat(Character.isSurrogate(sent.charAt(sent.length() - 1))).isTrue();
    assertThat(
            Character.isSurrogatePair(
                sent.charAt(sent.length() - 2), sent.charAt(sent.length() - 1)))
        .isTrue();
  }

  @Test
  void rejectsMissingMisorderedOrMalformedProviderVectors() {
    assertInvalidResponse(null);
    assertInvalidResponse(new EmbeddingResponse(List.of()));
    assertInvalidResponse(
        new EmbeddingResponse(
            List.of(
                new Embedding(new float[] {1.0f, 0.0f}, 0),
                new Embedding(new float[] {0.0f, 1.0f}, 0))));
    assertInvalidResponse(
        new EmbeddingResponse(
            List.of(
                new Embedding(new float[] {1.0f}, 0), new Embedding(new float[] {0.0f, 1.0f}, 1))));
    assertInvalidResponse(
        new EmbeddingResponse(
            List.of(
                new Embedding(new float[] {Float.NaN, 1.0f}, 0),
                new Embedding(new float[] {0.0f, 1.0f}, 1))));
    assertInvalidResponse(
        new EmbeddingResponse(
            List.of(
                new Embedding(new float[] {0.0f, -0.0f}, 0),
                new Embedding(new float[] {0.0f, 1.0f}, 1))));
  }

  @Test
  void mapsProviderFailuresWithoutExposingInputOrProviderMessage() {
    String sensitiveInput = "private memory content";
    String providerMessage = "upstream body contains credentials";
    var providerFailure = new IllegalStateException(providerMessage);
    var model =
        new StubEmbeddingModel(
            ignored -> {
              throw providerFailure;
            });

    assertThatThrownBy(
            () ->
                new SpringAiEmbeddingAdapter(model, options(2))
                    .embed(new EmbeddingRequest(List.of(sensitiveInput))))
        .isInstanceOf(EmbeddingInvocationException.class)
        .hasMessage("Embedding 调用失败")
        .hasMessageNotContaining(sensitiveInput)
        .hasMessageNotContaining(providerMessage)
        .hasCause(providerFailure);
  }

  @Test
  void validatesConfiguredModelDimensionsAndCodePointLimitBeforeCallingProvider() {
    var model =
        new StubEmbeddingModel(
            request -> new EmbeddingResponse(List.of(new Embedding(new float[] {1.0f, 0.0f}, 0))));

    assertThatThrownBy(() -> new SpringAiEmbeddingAdapter(model, options(0)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SpringAiEmbeddingAdapter(model, options(4097)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SpringAiEmbeddingAdapter(model, options(2), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SpringAiEmbeddingAdapter(model, options(2), 2001))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(model.calls()).isZero();
  }

  private static void assertInvalidResponse(EmbeddingResponse response) {
    var model = new StubEmbeddingModel(ignored -> response);

    assertThatThrownBy(
            () ->
                new SpringAiEmbeddingAdapter(model, options(2))
                    .embed(new EmbeddingRequest(List.of("first", "second"))))
        .isInstanceOf(InvalidEmbeddingResponseException.class)
        .hasMessage("Embedding 响应无效");
  }

  private static OpenAiEmbeddingOptions options(int dimensions) {
    var builder = OpenAiEmbeddingOptions.builder();
    builder.model("provider-model");
    builder.dimensions(dimensions);
    builder.user("private-provider-option");
    return builder.build();
  }

  private static final class StubEmbeddingModel implements EmbeddingModel {
    private final Function<org.springframework.ai.embedding.EmbeddingRequest, EmbeddingResponse>
        response;
    private int calls;
    private org.springframework.ai.embedding.EmbeddingRequest lastRequest;

    private StubEmbeddingModel(
        Function<org.springframework.ai.embedding.EmbeddingRequest, EmbeddingResponse> response) {
      this.response = response;
    }

    @Override
    public EmbeddingResponse call(org.springframework.ai.embedding.EmbeddingRequest request) {
      calls++;
      lastRequest = request;
      return response.apply(request);
    }

    @Override
    public float[] embed(Document document) {
      throw new UnsupportedOperationException("test only");
    }

    private int calls() {
      return calls;
    }

    private org.springframework.ai.embedding.EmbeddingRequest lastRequest() {
      return lastRequest;
    }
  }
}
