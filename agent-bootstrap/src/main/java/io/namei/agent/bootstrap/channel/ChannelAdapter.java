package io.namei.agent.bootstrap.channel;

public interface ChannelAdapter extends AutoCloseable {
  String name();

  void start();

  void stopAccepting();

  ChannelStatusSnapshot snapshot();

  @Override
  void close();
}
