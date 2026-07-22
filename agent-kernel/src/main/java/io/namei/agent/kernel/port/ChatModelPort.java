package io.namei.agent.kernel.port;

import io.namei.agent.kernel.concurrent.CancellationSignal;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import java.util.Objects;

/**
 * 核心层调用大语言模型的供应商无关端口。
 *
 * <p>适配器负责完成消息格式转换、工具调用解析、超时和供应商异常映射；不得把 Spring AI 或具体模型 SDK 类型暴露到该边界。
 */
@FunctionalInterface
public interface ChatModelPort {
  /**
   * 同步生成完整模型响应。
   *
   * @param request 已校验的模型请求
   * @return 标准化后的文本、工具调用及可选供应商元数据
   */
  ChatModelResponse generate(ChatModelRequest request);

  /**
   * 生成支持增量观察和协作取消的模型响应。
   *
   * <p>默认实现退化为同步调用，因此不保证产生增量事件；流式适配器应覆盖该方法，并确保取消会继续向底层传输传播。
   *
   * @param request 已校验的模型请求
   * @param observer 流式文本和状态观察者
   * @param cancellation 当前轮次的取消信号
   * @return 聚合完成后的标准模型响应
   */
  default ChatModelResponse generate(
      ChatModelRequest request, ChatModelStreamObserver observer, CancellationSignal cancellation) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(observer, "observer");
    Objects.requireNonNull(cancellation, "cancellation");
    cancellation.throwIfCancellationRequested();
    return generate(request);
  }
}
