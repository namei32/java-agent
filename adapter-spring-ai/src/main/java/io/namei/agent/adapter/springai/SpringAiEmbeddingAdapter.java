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

/**
 * 使用 Spring AI 实现核心 {@link EmbeddingPort} 的向量化适配器。
 *
 * <p>适配器限制单批文本数量与 Code Point 长度，并验证供应商返回数量、索引唯一性和向量维度；任何缺失、乱序重复或维度漂移都会映射为核心层非法响应异常。
 */
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

  /**
   * 批量生成向量，并按请求文本原始顺序返回经过校验的结果。
   *
   * @param request 与供应商无关的文本批次
   * @return 包含逻辑模型名、固定维度和有序向量的结果
   */
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
