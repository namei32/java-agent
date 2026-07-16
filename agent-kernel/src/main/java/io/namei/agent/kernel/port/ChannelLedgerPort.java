package io.namei.agent.kernel.port;

import io.namei.agent.kernel.channel.reliability.ChannelInstanceId;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerCommand;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerResult;
import java.util.Optional;

public interface ChannelLedgerPort {
  ChannelLedgerResult.Event recordEvent(ChannelLedgerCommand.RecordEvent command);

  ChannelLedgerResult.TurnStart startTurn(ChannelLedgerCommand.StartTurn command);

  ChannelLedgerResult.Terminal recordTerminal(ChannelLedgerCommand.RecordTerminal command);

  Optional<ChannelLedgerResult.DeliveryWork> claimNextDelivery(
      ChannelLedgerCommand.ClaimDelivery command);

  ChannelLedgerResult.DeliveryUpdate recordDeliveryOutcome(
      ChannelLedgerCommand.RecordDeliveryOutcome command);

  ChannelLedgerResult.Recovery recover(ChannelLedgerCommand.Recover command);

  ChannelLedgerResult.Cleanup cleanup(ChannelLedgerCommand.Cleanup command);

  ChannelLedgerResult.Snapshot snapshot(ChannelInstanceId instance);
}
