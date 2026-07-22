package io.namei.agent.application;

import io.namei.agent.kernel.proactive.SubagentRequest;
import io.namei.agent.kernel.proactive.SubagentResult;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 单次虚拟线程计算，继承取消信号并执行严格文本/时间预算。 */
public final class BoundedSubagentRunner {
  private final SubagentTask task;

  public BoundedSubagentRunner(SubagentTask task) {
    this.task = Objects.requireNonNull(task, "task");
  }

  public SubagentResult run(SubagentRequest request, TurnCancellation parentCancellation) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(parentCancellation, "parentCancellation");
    if (parentCancellation.isCancellationRequested()) {
      return SubagentResult.cancelled();
    }
    var submitted = new FutureTask<>(() -> task.execute(request));
    Thread.ofVirtual().name("proactive-subagent").start(submitted);
    try (var ignored = parentCancellation.onCancellation(() -> submitted.cancel(true))) {
      String text = submitted.get(request.budget().timeout().toNanos(), TimeUnit.NANOSECONDS);
      if (parentCancellation.isCancellationRequested()) {
        return SubagentResult.cancelled();
      }
      if (text == null
          || text.codePointCount(0, text.length()) > request.budget().maxResultCharacters()) {
        return SubagentResult.budgetExhausted();
      }
      return SubagentResult.completed(text);
    } catch (TimeoutException failure) {
      submitted.cancel(true);
      return SubagentResult.budgetExhausted();
    } catch (InterruptedException failure) {
      submitted.cancel(true);
      Thread.currentThread().interrupt();
      return SubagentResult.cancelled();
    } catch (CancellationException failure) {
      return SubagentResult.cancelled();
    } catch (ExecutionException failure) {
      return SubagentResult.budgetExhausted();
    }
  }
}
