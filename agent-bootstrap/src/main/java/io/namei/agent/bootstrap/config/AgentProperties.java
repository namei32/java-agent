package io.namei.agent.bootstrap.config;

import io.namei.agent.application.ModelStreamingSettings;
import io.namei.agent.application.ToolRuntimeMode;
import io.namei.agent.kernel.memory.MemoryRuntimeMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent")
public record AgentProperties(
    Path workspace, History history, Model model, ToolLoop toolLoop, Tools tools, Memory memory) {
  public AgentProperties {
    if (workspace == null) {
      throw new IllegalArgumentException("agent.workspace 必填");
    }
    history = history == null ? new History(40, 100_000) : history;
    model = model == null ? new Model(Duration.ofSeconds(60)) : model;
    toolLoop = toolLoop == null ? new ToolLoop(6) : toolLoop;
    tools = tools == null ? Tools.defaults() : tools;
    memory = memory == null ? Memory.defaults() : memory;
    if (tools.timeout().compareTo(model.timeout()) >= 0) {
      throw new IllegalArgumentException("agent.tools.timeout 必须小于 agent.model.timeout");
    }
  }

  public record Memory(
      MemoryRuntimeMode mode,
      long maxFileBytes,
      int maxContextCharacters,
      int maxRetrievedCharacters,
      Embedding embedding,
      Retrieval retrieval) {
    public Memory {
      Objects.requireNonNull(mode, "agent.memory.mode");
      embedding = embedding == null ? Embedding.defaults() : embedding;
      retrieval = retrieval == null ? Retrieval.defaults() : retrieval;
      if (maxFileBytes < 1
          || maxFileBytes > Integer.MAX_VALUE - 8L
          || maxContextCharacters < 1
          || maxRetrievedCharacters < 1) {
        throw new IllegalArgumentException("agent.memory 上限必须在有效范围内");
      }
      if (retrieval.maxInjectedCharacters() > maxRetrievedCharacters) {
        throw new IllegalArgumentException(
            "agent.memory.retrieval.max-injected-characters 不能超过外层检索上限");
      }
    }

    static Memory defaults() {
      return new Memory(
          MemoryRuntimeMode.DISABLED,
          65_536,
          100_000,
          20_000,
          Embedding.defaults(),
          Retrieval.defaults());
    }
  }

  public record Embedding(String model, int dimensions, int maxTextCodePoints) {
    public Embedding {
      if (model == null) {
        throw new IllegalArgumentException("agent.memory.embedding.model 必填");
      }
      model = model.strip();
      if (model.isBlank() || model.length() > 256) {
        throw new IllegalArgumentException("agent.memory.embedding.model 长度必须在 1..256");
      }
      if (dimensions < 1 || dimensions > 4096) {
        throw new IllegalArgumentException("agent.memory.embedding.dimensions 必须在 1..4096");
      }
      if (maxTextCodePoints < 1 || maxTextCodePoints > 2000) {
        throw new IllegalArgumentException(
            "agent.memory.embedding.max-text-code-points 必须在 1..2000");
      }
    }

    static Embedding defaults() {
      return new Embedding("text-embedding-v3", 1024, 2000);
    }
  }

  public record Retrieval(
      int topK,
      double scoreThreshold,
      double hotnessAlpha,
      double hotnessHalfLifeDays,
      int maxCandidates,
      int maxInjectedCharacters) {
    public Retrieval {
      if (topK < 1 || topK > 100) {
        throw new IllegalArgumentException("agent.memory.retrieval.top-k 必须在 1..100");
      }
      if (!Double.isFinite(scoreThreshold) || scoreThreshold < -1.0 || scoreThreshold > 1.0) {
        throw new IllegalArgumentException("agent.memory.retrieval.score-threshold 必须在 -1..1");
      }
      if (!Double.isFinite(hotnessAlpha) || hotnessAlpha < 0.0 || hotnessAlpha > 1.0) {
        throw new IllegalArgumentException("agent.memory.retrieval.hotness-alpha 必须在 0..1");
      }
      if (!Double.isFinite(hotnessHalfLifeDays) || hotnessHalfLifeDays <= 0.0) {
        throw new IllegalArgumentException("agent.memory.retrieval.hotness-half-life-days 必须大于零");
      }
      if (maxCandidates < topK || maxCandidates > 10_000) {
        throw new IllegalArgumentException(
            "agent.memory.retrieval.max-candidates 必须覆盖 top-k 且不超过 10000");
      }
      if (maxInjectedCharacters < 1) {
        throw new IllegalArgumentException("agent.memory.retrieval.max-injected-characters 必须大于零");
      }
    }

    static Retrieval defaults() {
      return new Retrieval(8, 0.45, 0.20, 14.0, 10_000, 6000);
    }
  }

  public record History(int maxMessages, int maxCharacters) {
    public History {
      if (maxMessages < 0 || maxCharacters < 0) {
        throw new IllegalArgumentException("历史窗口限制不能为负数");
      }
    }
  }

  public record Model(Duration timeout, int maxDeltaEvents, int maxDeltaCodePoints) {
    public Model(Duration timeout) {
      this(
          timeout,
          ModelStreamingSettings.defaults().maxDeltaEvents(),
          ModelStreamingSettings.defaults().maxDeltaCodePoints());
    }

    public Model {
      Objects.requireNonNull(timeout, "agent.model.timeout");
      if (timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("agent.model.timeout 必须为正数");
      }
      new ModelStreamingSettings(maxDeltaEvents, maxDeltaCodePoints);
    }
  }

  public record ToolLoop(int maxIterations) {
    public ToolLoop {
      if (maxIterations < 1) {
        throw new IllegalArgumentException("agent.tool-loop.max-iterations 必须大于零");
      }
    }
  }

  public record Tools(
      ToolRuntimeMode mode,
      int maxCallsPerResponse,
      int maxCallsPerTurn,
      Duration timeout,
      int maxConcurrentCalls,
      int maxArgumentBytes,
      int maxResultCharacters,
      Duration approvalTimeout) {
    public Tools(
        ToolRuntimeMode mode,
        int maxCallsPerResponse,
        int maxCallsPerTurn,
        Duration timeout,
        int maxConcurrentCalls,
        int maxArgumentBytes,
        int maxResultCharacters) {
      this(
          mode,
          maxCallsPerResponse,
          maxCallsPerTurn,
          timeout,
          maxConcurrentCalls,
          maxArgumentBytes,
          maxResultCharacters,
          Duration.ofMinutes(5));
    }

    public Tools {
      Objects.requireNonNull(mode, "agent.tools.mode");
      Objects.requireNonNull(timeout, "agent.tools.timeout");
      Objects.requireNonNull(approvalTimeout, "agent.tools.approval-timeout");
      if (maxCallsPerResponse < 1
          || maxCallsPerTurn < 1
          || maxConcurrentCalls < 1
          || maxArgumentBytes < 1
          || maxResultCharacters < 1
          || timeout.isZero()
          || timeout.isNegative()) {
        throw new IllegalArgumentException("agent.tools 预算必须大于零");
      }
      if (maxCallsPerTurn < maxCallsPerResponse) {
        throw new IllegalArgumentException("agent.tools.max-calls-per-turn 不能小于单响应上限");
      }
      if (approvalTimeout.isZero()
          || approvalTimeout.isNegative()
          || approvalTimeout.compareTo(Duration.ofMinutes(15)) > 0) {
        throw new IllegalArgumentException("agent.tools.approval-timeout 必须大于零且不超过 15m");
      }
    }

    static Tools defaults() {
      return new Tools(
          ToolRuntimeMode.READ_ONLY,
          8,
          16,
          Duration.ofSeconds(5),
          32,
          16_384,
          20_000,
          Duration.ofMinutes(5));
    }
  }
}
