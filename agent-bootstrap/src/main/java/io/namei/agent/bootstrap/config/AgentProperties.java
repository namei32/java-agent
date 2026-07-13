package io.namei.agent.bootstrap.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent")
public record AgentProperties(Path workspace, History history, Model model, ToolLoop toolLoop) {
  public AgentProperties(Path workspace, History history, Model model) {
    this(workspace, history, model, null);
  }

  public AgentProperties {
    if (workspace == null) {
      throw new IllegalArgumentException("agent.workspace 必填");
    }
    history = history == null ? new History(40, 100_000) : history;
    model = model == null ? new Model(Duration.ofSeconds(60)) : model;
    toolLoop = toolLoop == null ? new ToolLoop(6) : toolLoop;
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
}
