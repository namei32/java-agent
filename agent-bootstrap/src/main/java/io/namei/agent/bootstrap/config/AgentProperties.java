package io.namei.agent.bootstrap.config;

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
      int maxRetrievedCharacters) {
    public Memory {
      Objects.requireNonNull(mode, "agent.memory.mode");
      if (maxFileBytes < 1
          || maxFileBytes > Integer.MAX_VALUE - 8L
          || maxContextCharacters < 1
          || maxRetrievedCharacters < 1) {
        throw new IllegalArgumentException("agent.memory 上限必须在有效范围内");
      }
    }

    static Memory defaults() {
      return new Memory(MemoryRuntimeMode.DISABLED, 65_536, 100_000, 20_000);
    }
  }

  public record History(int maxMessages, int maxCharacters) {
    public History {
      if (maxMessages < 0 || maxCharacters < 0) {
        throw new IllegalArgumentException("历史窗口限制不能为负数");
      }
    }
  }

  public record Model(Duration timeout) {
    public Model {
      Objects.requireNonNull(timeout, "agent.model.timeout");
      if (timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("agent.model.timeout 必须为正数");
      }
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
