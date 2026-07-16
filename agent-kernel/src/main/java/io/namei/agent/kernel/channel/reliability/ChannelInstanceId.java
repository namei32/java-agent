package io.namei.agent.kernel.channel.reliability;

public record ChannelInstanceId(String channel, String value) {
  public ChannelInstanceId {
    channel = ChannelReliabilityValues.channel(channel);
    value = ChannelReliabilityValues.hash(value, "Channel Instance ID");
  }

  public static ChannelInstanceId derive(String channel, String trustedInstanceKey) {
    String normalizedChannel = ChannelReliabilityValues.channel(channel);
    String normalizedKey = ChannelReliabilityValues.trustedInstanceKey(trustedInstanceKey);
    return new ChannelInstanceId(
        normalizedChannel,
        ChannelFingerprint.hashFields(
            ChannelFingerprint.INSTANCE_VERSION, normalizedChannel, normalizedKey));
  }

  @Override
  public String toString() {
    return "ChannelInstanceId[channel=" + channel + ", value=<redacted>]";
  }
}
