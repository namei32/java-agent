package io.namei.agent.application;

@FunctionalInterface
public interface ChatProgressListener {
  void onContentDelta(String contentDelta);

  static ChatProgressListener noop() {
    return ignored -> {};
  }
}
