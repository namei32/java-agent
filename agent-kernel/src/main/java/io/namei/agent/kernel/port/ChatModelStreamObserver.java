package io.namei.agent.kernel.port;

@FunctionalInterface
public interface ChatModelStreamObserver {
  void onContentDelta(String contentDelta);

  static ChatModelStreamObserver noop() {
    return ignored -> {};
  }
}
