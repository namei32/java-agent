package io.namei.agent.adapter.springai;

import io.namei.agent.kernel.error.EmbeddingInvocationException;
import io.namei.agent.kernel.error.InvalidEmbeddingResponseException;
import io.namei.agent.kernel.memory.EmbeddingRequest;
import io.namei.agent.kernel.memory.EmbeddingResult;
import io.namei.agent.kernel.memory.EmbeddingVector;
import io.namei.agent.kernel.port.EmbeddingPort;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingResponse;

public final class SpringAiEmbeddingAdapter implements EmbeddingPort {
  private static final int MAX_BATCH_SIZE = 10;
  private static final int DEFAULT_MAX_TEXT_CODE_POINTS = 2000;

  private final EmbeddingModel embeddingModel;
  private final EmbeddingOptions providerOptions;
  private final String logicalModel;
  private final int dimensions;
  private final int maxTextCodePoints;

  public SpringAiEmbeddingAdapter(EmbeddingModel embeddingModel, EmbeddingOptions providerOptions) {
    this(embeddingModel, providerOptions, DEFAULT_MAX_TEXT_CODE_POINTS);
  }

  public SpringAiEmbeddingAdapter(
      EmbeddingModel embeddingModel, EmbeddingOptions providerOptions, int maxTextCodePoints) {
    this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
    this.providerOptions = Objects.requireNonNull(providerOptions, "providerOptions");
    Integer configuredDimensions = providerOptions.getDimensions();
    if (configuredDimensions == null) {
      throw new IllegalArgumentException("Embedding 维度不能为空");
    }
    var configured =
        new EmbeddingResult(providerOptions.getModel(), configuredDimensions, List.of());
    this.logicalModel = configured.model();
    this.dimensions = configured.dimensions();
    if (maxTextCodePoints < 1 || maxTextCodePoints > DEFAULT_MAX_TEXT_CODE_POINTS) {
      throw new IllegalArgumentException("Embedding 文本 Code Point 上限必须在 1..2000");
    }
    this.maxTextCodePoints = maxTextCodePoints;
  }

  @Override
  public EmbeddingResult embed(EmbeddingRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.texts().size() > MAX_BATCH_SIZE) {
      throw new IllegalArgumentException("Embedding 单批不能超过 10 条");
    }
    if (request.texts().isEmpty()) {
      return new EmbeddingResult(logicalModel, dimensions, List.of());
    }

    List<String> texts = request.texts().stream().map(this::normalizeText).toList();
    EmbeddingResponse response;
    try {
      response =
          embeddingModel.call(
              new org.springframework.ai.embedding.EmbeddingRequest(texts, providerOptions));
    } catch (RuntimeException exception) {
      throw new EmbeddingInvocationException(exception);
    }
    return validateResponse(response, texts.size());
  }

  private EmbeddingResult validateResponse(EmbeddingResponse response, int expectedCount) {
    try {
      if (response == null
          || response.getResults() == null
          || response.getResults().size() != expectedCount) {
        throw invalidResponse();
      }
      var ordered = new EmbeddingVector[expectedCount];
      for (Embedding embedding : response.getResults()) {
        if (embedding == null
            || embedding.getIndex() == null
            || embedding.getIndex() < 0
            || embedding.getIndex() >= expectedCount
            || ordered[embedding.getIndex()] != null) {
          throw invalidResponse();
        }
        EmbeddingVector vector = new EmbeddingVector(embedding.getOutput());
        if (vector.dimensions() != dimensions) {
          throw invalidResponse();
        }
        ordered[embedding.getIndex()] = vector;
      }
      if (Arrays.stream(ordered).anyMatch(Objects::isNull)) {
        throw invalidResponse();
      }
      return new EmbeddingResult(logicalModel, dimensions, List.of(ordered));
    } catch (InvalidEmbeddingResponseException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalidResponse();
    }
  }

  private String normalizeText(String input) {
    String stripped = input.strip();
    int end = 0;
    int codePoints = 0;
    while (end < stripped.length() && codePoints < maxTextCodePoints) {
      int codePoint = stripped.codePointAt(end);
      end += Character.charCount(codePoint);
      codePoints++;
    }
    return end == stripped.length() ? stripped : stripped.substring(0, end);
  }

  private static InvalidEmbeddingResponseException invalidResponse() {
    return new InvalidEmbeddingResponseException();
  }
}
