package io.namei.agent.kernel.concurrent;

import io.namei.agent.kernel.error.TurnCancelledException;
import java.util.Objects;

/**
 * 核心层使用的协作取消信号。
 *
 * <p>长耗时模型、工具或渠道操作应注册回调释放底层资源，并在提交不可逆状态前调用 {@link #throwIfCancellationRequested()}。注册返回值必须关闭，以免回调跨
 * Turn 泄漏。
 */
public interface CancellationSignal {
  /** 返回当前是否已经请求取消。 */
  boolean isCancellationRequested();

  /**
   * 注册一次取消回调；信号已经取消时实现应及时触发。
   *
   * @return 用于解除回调的注册句柄
   */
  Registration onCancellation(Runnable callback);

  /** 在已经取消时抛出核心层统一的 {@link TurnCancelledException}。 */
  default void throwIfCancellationRequested() {
    if (isCancellationRequested()) {
      throw new TurnCancelledException("当前操作已取消");
    }
  }

  static CancellationSignal none() {
    return NeverCancelled.INSTANCE;
  }

  /** 可关闭的取消回调注册。 */
  @FunctionalInterface
  interface Registration extends AutoCloseable {
    @Override
    void close();
  }

  enum NeverCancelled implements CancellationSignal {
    INSTANCE;

    @Override
    public boolean isCancellationRequested() {
      return false;
    }

    @Override
    public Registration onCancellation(Runnable callback) {
      Objects.requireNonNull(callback, "callback");
      return () -> {};
    }
  }
}
